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
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen + audio recorder on MediaCodec + MediaMuxer. MediaRecorder has no
 * API to capture another app's playback audio, so we run the Google-recommended
 * pipeline instead:
 *
 *   video: MediaProjection -> VirtualDisplay -> H264 encoder input surface
 *   audio: AudioRecord with AudioPlaybackCapture (API 29+) = the PHONE's own
 *          media playback (the reel's song), NOT the room mic. Falls back to
 *          the microphone on API 26-28 / when no capturable source exists.
 *   mux:   MediaMuxer, MPEG-4
 *
 * MediaMuxer forbids addTrack() after start() (IllegalStateException), but the
 * two codec formats (with SPS/PPS csd) arrive at different times, so encoded
 * samples are buffered until BOTH tracks are registered; a watchdog starts the
 * muxer after 1.5s even if audio never appears (e.g. nothing capturable), so a
 * silent reel still saves video.
 *
 * ponytail: one global muxer lock; per-track locks if writes ever contend.
 */
class RecorderEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val outFile: File
) {
    interface Listener {
        fun onError(msg: String)
        /** Capture was ended by the system/user (cast notification swiped, permission revoked). */
        fun onStopped()
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
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFormatReceived = false
    private var audioFormatReceived = false
    private var videoFormatReceivedTimeMs = 0L

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var inputSurface: Surface? = null

    private val audioEos = CountDownLatch(1)
    private val videoEos = CountDownLatch(1)
    private val pumpDone = CountDownLatch(1)
    private var audioPtsUs = 0L

    /** Encoded samples that arrived before the muxer started (guarded by muxerLock). */
    private class Pending(val track: Int, val data: ByteArray, val pts: Long, val flags: Int)
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0
    private val PENDING_CAP = 12 * 1024 * 1024 // drop-oldest past this; only ~1 frame anyway

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

        val vc = MediaCodec.createEncoderByType("video/avc")
        val vFormat = MediaFormat.createVideoFormat("video/avc", width(), height()).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        vc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vc.createInputSurface()
        vc.setCallback(videoCallback)
        vc.start()
        videoCodec = vc
        inputSurface = surface

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // API 34 REQUIRES a registered callback before createVirtualDisplay, or it
        // throws "Must register a callback before starting capture". It's also how
        // we learn the user revoked capture (swiped the cast notification).
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Runs on the main looper; stop() can block a few seconds
                    // draining encoders, so finalize off-thread.
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

        virtualDisplay = projection.createVirtualDisplay(
            "FreeSongRecorder", width(), height(), context.resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        Thread { pumpAudio() }.start()
        // Watchdog: poll the muxer-start condition (video-first) so the audio
        // has a grace period but the muxer never starts on audio alone.
        Thread {
            while (running.get() && !muxerStarted) {
                Thread.sleep(300)
                synchronized(muxerLock) { maybeStartMuxer() }
            }
        }.start()
    }

    /** Stop cleanly (EOS both encoders, drain, release). Safe to call twice. */
    fun stop() {
        if (!running.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Exception) {} // unblocks pumpAudio read
        pumpDone.await(2, TimeUnit.SECONDS)
        queueAudioEos()
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        audioEos.await(2, TimeUnit.SECONDS)
        videoEos.await(2, TimeUnit.SECONDS)
        release()
    }

    // ---- audio input pump ---------------------------------------------------

    private fun pumpAudio() {
        val ar = audioRecord ?: return
        val buf = ByteArray(AUDIO_BUF)
        while (running.get()) {
            val n = try {
                ar.read(buf, 0, buf.size)
            } catch (e: Exception) {
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

    // ---- encoders -> muxer ---------------------------------------------------

    private val audioCallback = object : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            writeSample(codec, index, info, audio = true)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioEos.countDown()
        }
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener?.onError(e.message ?: "audio codec error")
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(muxerLock) {
                muxer?.let {
                    // Never addTrack after the muxer started (addTrack on a started
                    // muxer throws IllegalStateException = crash from this thread).
                    if (muxerStarted) return@synchronized
                    audioTrack = it.addTrack(format)
                    audioFormatReceived = true
                    maybeStartMuxer()
                }
            }
        }
    }

    private val videoCallback = object : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            writeSample(codec, index, info, audio = false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoEos.countDown()
        }
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener?.onError(e.message ?: "video codec error")
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(muxerLock) {
                muxer?.let {
                    // Same guard as audio: never addTrack after the muxer started.
                    if (muxerStarted) return@synchronized
                    videoTrack = it.addTrack(format)
                    videoFormatReceived = true
                    videoFormatReceivedTimeMs = System.currentTimeMillis()
                    maybeStartMuxer()
                }
            }
        }
    }

    private fun writeSample(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, audio: Boolean) {
        val out = codec.getOutputBuffer(index)
        val track = if (audio) audioTrack else videoTrack
        if (out != null && info.size > 0 && track >= 0) {
            val bytes = ByteArray(info.size)
            out.position(info.offset)
            out.get(bytes)
            synchronized(muxerLock) {
                if (muxerStarted) {
                    val m = muxer ?: return@synchronized
                    try {
                        m.writeSampleData(track, java.nio.ByteBuffer.wrap(bytes), info)
                    } catch (_: Exception) {}
                } else if (pendingBytes < PENDING_CAP) {
                    pending.addLast(Pending(track, bytes, info.presentationTimeUs, info.flags))
                    pendingBytes += bytes.size
                }
            }
        }
        codec.releaseOutputBuffer(index, false)
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || !videoFormatReceived) return
        // Video-first: never start the muxer on audio alone (an audio-only MP4 is
        // garbage). Audio gets a 3s grace after the video format, then we start
        // video-only rather than block forever.
        val audioReady = audioFormatReceived
        val audioTimedOut = !audioReady && videoFormatReceivedTimeMs > 0 &&
            (System.currentTimeMillis() - videoFormatReceivedTimeMs) > AUDIO_TRACK_TIMEOUT_MS
        if (audioReady || audioTimedOut) startMuxer()
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
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
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
            AUDIO_BUF
        )
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
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    ar.startRecording()
                    if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return ar to "Internal audio (phone)"
                    }
                    ar.release()
                }
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

    private fun width(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val real = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
        val scale = minOf(1f, 1280f / maxOf(real.x, 1).toFloat())
        var w = (real.x * scale).toInt() and 0x7FFFFFFE
        if (w < 2) w = 720
        return w
    }

    private fun height(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val real = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
        val scale = minOf(1f, 1280f / maxOf(real.x, 1).toFloat())
        var h = (real.y * scale).toInt() and 0x7FFFFFFE
        if (h < 2) h = 1280
        return h
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val AUDIO_BUF = 8192
        private const val TIMEOUT_US = 10_000L
        private const val AUDIO_TRACK_TIMEOUT_MS = 3_000L
    }
}
