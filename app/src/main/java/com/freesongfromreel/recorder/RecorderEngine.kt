package com.freesongfromreel.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AUDIO-ONLY recorder on MediaCodec + MediaMuxer.
 *
 * Captures the phone's OWN media playback (the reel's song) into a small MP4
 * (audio track only). The user does not want the video — and dropping the
 * video encoder makes the "mixing/saving" phase near-instant instead of the
 * multi-minute drain we saw with H264.
 *
 * The MediaProjection is STILL REQUIRED — Android only lets an app capture
 * another app's audio through a MediaProjection token (AudioPlaybackCapture).
 * We just never create a virtual display / video encoder from it.
 *
 *   audio: AudioRecord with AudioPlaybackCapture (API 29+) = the phone's own
 *          media playback, NOT the room mic. Falls back to the microphone on
 *          API 26-28 / when no capturable source exists.
 *   mux:   MediaMuxer, MPEG-4 (audio track only)
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
    private val muxerLock = Any()

    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var audioTrack = -1
    private var audioFormatReceived = false

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private val audioEos = CountDownLatch(1)
    private val pumpDone = CountDownLatch(1)
    private var audioPtsUs = 0L

    /** Encoded samples that arrived before the muxer started (guarded by muxerLock). */
    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0
    private val PENDING_CAP = 12 * 1024 * 1024

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        val (ar, source) = buildAudioRecord()
        audioRecord = ar
        audioSourceName = source
        // buildAudioRecord already startRecording()'d it (with a state check).

        val ac = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val aFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        ac.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        ac.setCallback(audioCallback)
        ac.start()
        audioCodec = ac

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Still register so a revoked projection stops us cleanly (and Android
        // expects a registered callback for capture sessions).
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Runs on the main looper; stop() can block a moment, so
                    // finalize off-thread.
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
        // Watchdog: start the muxer as soon as the audio format is in. Audio-only
        // so there's no video to wait on — this fires within ~100ms of start.
        Thread {
            while (running.get() && !muxerStarted) {
                Thread.sleep(100)
                synchronized(muxerLock) { maybeStartMuxer() }
            }
        }.start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Exception) {} // unblocks pumpAudio read
        pumpDone.await(2, TimeUnit.SECONDS)
        queueAudioEos()
        audioEos.await(2, TimeUnit.SECONDS)
        listener?.onStopProgress(100)
        release()
    }

    // ---- audio input pump ---------------------------------------------------

    private fun pumpAudio() {
        val ar = audioRecord ?: return
        val buf = ByteArray(AUDIO_BUF)
        while (running.get()) {
            val n = try {
                ar.read(buf, 0, buf.size)
            } catch (_: Exception) {
                // e.g. AudioRecord released/stopped under us — stop the thread
                // instead of crashing the process (uncaught in a thread = app crash).
                break
            }
            if (n <= 0) {
                if (!running.get()) break
                continue
            }
            try {
                feedAudio(buf, n)
            } catch (_: Exception) {
                break
            }
        }
        pumpDone.countDown()
    }

    private fun feedAudio(data: ByteArray, len: Int) {
        val codec = audioCodec ?: return
        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (idx < 0) return
        codec.getInputBuffer(idx)?.let {
            it.clear()
            it.put(data, 0, len)
            codec.queueInputBuffer(idx, 0, len, audioPtsUs, 0)
            audioPtsUs += len * 1_000_000L / (SAMPLE_RATE * CHANNELS * 2)
        }
    }

    private fun queueAudioEos() {
        val codec = audioCodec ?: return
        val idx = codec.dequeueInputBuffer(TIMEOUT_US)
        if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    // ---- encoder -> muxer ---------------------------------------------------

    private val audioCallback = object : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            writeSample(codec, index, info)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioEos.countDown()
        }
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener?.onError(e.message ?: "audio codec error")
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            try {
                synchronized(muxerLock) {
                    muxer?.let {
                        // Never addTrack after the muxer started.
                        if (muxerStarted) return@synchronized
                        audioTrack = it.addTrack(format)
                        audioFormatReceived = true
                        maybeStartMuxer()
                    }
                }
            } catch (_: Exception) {
                // Codec released mid-stop; addTrack throws here uncaught = crash.
            }
        }
    }

    private fun writeSample(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val out = codec.getOutputBuffer(index)
            if (out != null && info.size > 0 && audioTrack >= 0) {
                val bytes = ByteArray(info.size)
                out.position(info.offset)
                out.get(bytes)
                synchronized(muxerLock) {
                    if (muxerStarted) {
                        val m = muxer ?: return@synchronized
                        try {
                            m.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(bytes), info)
                        } catch (_: Exception) {}
                    } else if (pendingBytes < PENDING_CAP) {
                        pending.addLast(Pending(audioTrack, bytes, info.presentationTimeUs, info.flags))
                        pendingBytes += bytes.size
                    }
                }
            }
        } catch (_: Exception) {
            // Codec can be mid-release during stop() — an exception here would
            // run UNCAUGHT on the codec callback thread = whole-process crash.
        }
        try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || !audioFormatReceived) return
        startMuxer()
    }

    private fun startMuxer() {
        val m = muxer ?: return
        try {
            m.start()
            muxerStarted = true
            val flush = ArrayList<Pending>(pending)
            pending.clear()
            pendingBytes = 0
            for (p in flush) m.writeSampleData(p.track, java.nio.ByteBuffer.wrap(p.data),
                MediaCodec.BufferInfo().apply {
                    this.presentationTimeUs = p.pts
                    this.flags = p.flags
                    this.offset = 0
                    this.size = p.data.size
                })
        } catch (_: Exception) {
            muxerStarted = false
        }
    }

    // ---- teardown -------------------------------------------------------------

    private fun release() {
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        synchronized(muxerLock) {
            val m = muxer
            if (m != null) {
                try {
                    if (muxerStarted) m.stop()
                    m.release()
                } catch (_: Exception) {}
            }
            muxer = null
        }
    }

    // ---- helpers ---------------------------------------------------------------

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
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val AUDIO_BUF = 8192
        private const val TIMEOUT_US = 10_000L
    }
}