package com.freesongfromreel.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * Foreground MediaProjection screen recorder. Writes MP4 to
 * filesDir/recordings/<timestamp>.mp4.
 *
 * ponytail: MVP records MIC audio (simple, works on all API 26+). Internal
 * app audio (the song) needs AudioPlaybackCapture (API 31+) — wire that when
 * this becomes a real product, it's the difference between "records the room"
 * and "records the reel".
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                startRecording(resultCode, data)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent?) {
        if (data == null) { stopSelf(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj

        val file = File(getExternalFilesDir(null) ?: filesDir, "recordings")
        if (!file.exists()) file.mkdirs()
        val out = File(file, "rec_${System.currentTimeMillis()}.mp4")
        outputFile = out

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(720, 1280)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(4_000_000)
        r.setAudioEncodingBitRate(128_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()

        val density = resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "FreeSongRecorder", 720, 1280, density,
            android.view.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        recorder = r

        Notification(this, "Recording…", "Recording screen")
            .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, it) }
    }

    private fun startForegroundCompat() {
        val ch = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification(this, "Free Song Recorder", "Ready to record")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        virtualDisplay?.release()
        projection?.stop()
        recorder = null
        projection = null
        virtualDisplay = null
        super.onDestroy()
    }

    fun lastRecording(): File? = outputFile

    private fun Notification(context: Context, title: String, text: String): Notification =
        android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()

    companion object {
        const val ACTION_START = "com.freesongfromreel.recorder.START"
        const val ACTION_STOP = "com.freesongfromreel.recorder.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
    }
}