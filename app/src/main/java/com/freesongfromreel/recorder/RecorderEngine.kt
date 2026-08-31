package com.freesongfromreel.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AUDIO-ONLY recorder writing raw PCM to a WAV file.
 *
 * Captures the phone's OWN media playback (the reel's song) into a WAV.
 * INTERNAL AUDIO ONLY — never the microphone (by design; the mic would pick up
 * room noise and the user explicitly does not want it).
 *
 * The MediaProjection is REQUIRED — Android only lets an app capture another
 * app's audio through a MediaProjection token (AudioPlaybackCapture).
 *
 *   audio: AudioRecord with AudioPlaybackCapture (API 29+) = the phone's own
 *          media playback. On API 26-28 / when no capturable source exists we
 *          fail loudly instead of silently recording silence.
 *   out:   WAV (44-byte RIFF header + PCM16 stereo 44.1kHz)
 */
class RecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val outFile: File
) {
    interface Listener {
        fun onError(msg: String)
        /** Capture was ended by the system/user (projection revoked). */
        fun onStopped()
        /** Progress of the finalize (0-100) — called from stop(). */
        fun onStopProgress(percent: Int)
    }

    var listener: Listener? = null

    @Volatile
    var audioSourceName: String = "Internal audio (phone)"
        private set

    private val running = AtomicBoolean(true)

    private var audioRecord: AudioRecord? = null
    private var outStream: FileOutputStream? = null
    private var dataBytes = 0L

    /** Captured PCM bytes written so far (for diagnostics / Saved message). */
    val bytesRecorded: Long get() = dataBytes

    // ---- lifecycle ---------------------------------------------------------

    fun start(): String? {
        val ar = buildInternalRecord()
        if (ar == null) {
            // No mic fallback — fail loudly so the user knows capture didn't start.
            return "Could not start internal audio capture (nothing capturable or API < 29)"
        }
        audioRecord = ar
        audioSourceName = "Internal audio (phone)"

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Thread {
                        try {
                            stop()
                            listener?.onStopped()
                        } catch (_: Exception) {}
                    }.start()
                }
            },
            null
        )

        Thread { pumpAudio() }.start()
        return null
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        listener?.onStopProgress(50)
        ending = true
        try { audioRecord?.stop() } catch (_: Exception) {} // unblocks pumpAudio read
        // Give the pump thread a moment to flush + close the stream itself.
        pumpDone.await(2, TimeUnit.SECONDS)
        patchWavHeader()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        listener?.onStopProgress(100)
    }

    @Volatile
    private var ending = false
    private val pumpDone = CountDownLatch(1)

    // ---- audio input pump ---------------------------------------------------

    private fun pumpAudio() {
        val ar = audioRecord ?: return
        val buf = ByteArray(BUF_SIZE)
        var stream: FileOutputStream? = null
        try {
            stream = FileOutputStream(outFile)
            outStream = stream
            writeWavHeader(stream)
            while (running.get() || !ending) {
                val n = try {
                    ar.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) {
                    // Blocked read or no data; keep looping until stop() unblocks us.
                    if (!running.get() && ending) break
                    try { Thread.sleep(20) } catch (_: Exception) {}
                    continue
                }
                try {
                    stream.write(buf, 0, n)
                    dataBytes += n
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
            // File I/O failed — nothing we can do; stop() patches what it can.
        } finally {
            try { stream?.flush() } catch (_: Exception) {}
            try { stream?.close() } catch (_: Exception) {}
            outStream = null
            pumpDone.countDown()
        }
    }

    private fun writeWavHeader(os: FileOutputStream) {
        // 44-byte RIFF/WAVE header. Data size patched in stop().
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray(Charsets.US_ASCII))
        hdr.putInt(0) // file size - 8, patched later
        hdr.put("WAVE".toByteArray(Charsets.US_ASCII))
        hdr.put("fmt ".toByteArray(Charsets.US_ASCII))
        hdr.putInt(16)            // fmt chunk size
        hdr.putShort(1)           // PCM
        hdr.putShort(2)           // channels (stereo)
        hdr.putInt(SAMPLE_RATE)
        hdr.putInt(SAMPLE_RATE * 2 * 2) // byte rate
        hdr.putShort((2 * 2).toShort()) // block align
        hdr.putShort(16)          // bits per sample
        hdr.put("data".toByteArray(Charsets.US_ASCII))
        hdr.putInt(0)             // data size, patched later
        os.write(hdr.array())
    }

    private fun patchWavHeader() {
        val f = outFile
        if (!f.exists()) return
        try {
            java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.seek(4)
                raf.writeInt(((44 - 8) + dataBytes).toInt())
                raf.seek(40)
                raf.writeInt(dataBytes.toInt())
            }
        } catch (_: Exception) {}
    }

    // ---- helpers ---------------------------------------------------------------

    private fun buildInternalRecord(): AudioRecord? {
        if (Build.VERSION.SDK_INT < 29) return null
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4,
            32 * 1024
        )
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val ar = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
            ar.startRecording()
            if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar else { ar.release(); null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val BUF_SIZE = 8192
    }
}