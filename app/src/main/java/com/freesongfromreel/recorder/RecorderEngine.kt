package com.freesongfromreel.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * No MediaCodec / MediaMuxer — raw PCM + RIFF/WAV header is trivially
 * decodable by the backend (ffmpeg -i file.wav always works), with none of
 * the AAC/muxer finalization headaches that produced broken MP4s.
 *
 * The MediaProjection is STILL REQUIRED — Android only lets an app capture
 * another app's audio through a MediaProjection token (AudioPlaybackCapture).
 * We just never create a virtual display / video encoder from it.
 *
 *   audio: AudioRecord with AudioPlaybackCapture (API 29+) = the phone's own
 *          media playback, NOT the room mic. Falls back to the microphone on
 *          API 26-28 / when no capturable source exists.
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

    /** "Internal audio (phone)" or "Microphone" — set after start(). */
    @Volatile
    var audioSourceName: String = "Microphone"
        private set

    private val running = AtomicBoolean(true)

    private var audioRecord: AudioRecord? = null
    private var outStream: FileOutputStream? = null
    private var dataBytes = 0L

    /** Captured PCM bytes written so far (for diagnostics / Saved message). */
    val bytesRecorded: Long get() = dataBytes

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        val (ar, source) = buildAudioRecord()
        audioRecord = ar
        audioSourceName = source
        // buildAudioRecord already startRecording()'d it (with a state check).

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
        Thread { watchdog() }.start()
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
    private val pumpDone = java.util.concurrent.CountDownLatch(1)

    // If the playback-capture AudioRecord delivers NOTHING (some devices/drivers
    // block in read() waiting for a capturable source), the pump never sees data
    // and the file stays a 44-byte header. Watch the byte count on a SEPARATE
    // thread; after FALLBACK_MS of no progress, swap to the mic (which always
    // delivers). Releasing the old record unblocks its stuck read().
    private fun watchdog() {
        val lastWrite = longArrayOf(System.currentTimeMillis())
        while (running.get()) {
            Thread.sleep(500)
            if (dataBytes > lastWrite[0]) {
                lastWrite[0] = dataBytes
                continue
            }
            // No new bytes written since last tick.
            if (audioSourceName.startsWith("Internal") &&
                System.currentTimeMillis() - lastWrite[0] > FALLBACK_MS) {
                android.util.Log.w(TAG, "internal capture starved; switching to mic")
                val mic = buildMicRecord()
                if (mic != null) {
                    val old = audioRecord
                    try { old?.stop() } catch (_: Exception) {}
                    try { old?.release() } catch (_: Exception) {}
                    audioRecord = mic
                    audioSourceName = "Microphone (fallback)"
                }
                lastWrite[0] = dataBytes
            }
        }
    }

    // ---- audio input pump ---------------------------------------------------

    private fun pumpAudio() {
        val buf = ByteArray(BUF_SIZE)
        var stream: FileOutputStream? = null
        try {
            stream = FileOutputStream(outFile)
            outStream = stream
            writeWavHeader(stream)
            while (running.get() || !ending) {
                val ar = audioRecord ?: break
                val n = try {
                    ar.read(buf, 0, buf.size)
                } catch (_: Exception) {
                    -1
                }
                if (n < 0) {
                    // Could be a released record from the watchdog swap — the
                    // field now points at the mic; loop again to use it. If we're
                    // ending, bail (stop() closes the header).
                    if (!running.get() && ending) break
                    if (audioRecord === ar) {
                        // Same record, no data, not ending: brief pause to avoid
                        // a hot spin on a record that's just returning -1.
                        try { Thread.sleep(50) } catch (_: Exception) {}
                    }
                    continue
                }
                if (n == 0) {
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

    private fun buildMicRecord(): AudioRecord? {
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
            val ar = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
            ar.startRecording()
            if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) ar else { ar.release(); null }
        } catch (_: Exception) { null }
    }

    private fun buildAudioRecord(): Pair<AudioRecord?, String> {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4,
            32 * 1024
        )
        // Internal audio (the OTHER app's playback) on API 29+. This is the whole
        // point of the app — the reel's song, not the room.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
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
                if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    return ar to "Internal audio (phone)"
                }
                ar.release()
            } catch (_: Exception) {
                // fall through to microphone
            }
        }
        val ar = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
            .build()
        ar.startRecording()
        return ar to "Microphone (room sound)"
    }

    companion object {
        private const val TAG = "RecorderEngine"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val BUF_SIZE = 8192
        private const val FALLBACK_MS = 2_500L
    }
}