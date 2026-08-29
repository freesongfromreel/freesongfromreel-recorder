package com.freesongfromreel.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.hardware.display.DisplayManager
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
    private var pendingPublish: File? = null

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
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    /** Stop the recorder + virtual display + service. Safe to call anytime. */
    private fun stopRecording() {
        ServiceState.isRecording = false
        stopMedia()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED)
            .putExtra(EXTRA_FILE, outputFile?.absolutePath))
        stopSelf()
    }

    /** Release the MediaRecorder exactly once (shared by stop + cleanup). */
    private fun stopMedia() {
        val r = recorder
        recorder = null
        if (r != null) {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        pendingPublish?.let { publishToMediaStore(it); pendingPublish = null }
    }

    private fun startRecording(resultCode: Int, data: Intent?) {
        ServiceState.isRecording = true
        try {
            startRecordingInner(resultCode, data)
        } catch (e: Exception) {
            // Never crash the app — clean up, drop the FGS notification,
            // and tell the user (and the Activity) what happened.
            ServiceState.isRecording = false
            ServiceState.lastError = e.message ?: "Could not start recording"
            val errMsg = ServiceState.lastError ?: "Could not start recording"
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            Notification(this, "Recording failed", errMsg)
                .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID + 1, it) }
        }
    }

    private fun startRecordingInner(resultCode: Int, data: Intent?) {
        if (data == null) { stopSelf(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj

        // Use the device's real screen size (fallback 720p), clamped + even dims.
        // Hardcoded sizes can be rejected by some displays/encoders → crash.
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val real = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
        val scale = minOf(1f, 1280f / maxOf(real.x, 1).toFloat())
        var w = (real.x * scale).toInt() and 0x7FFFFFFE
        var h = (real.y * scale).toInt() and 0x7FFFFFFE
        if (w < 2) w = 720
        if (h < 2) h = 1280

        val file = File(getExternalFilesDir(null) ?: filesDir, "recordings")
        if (!file.exists()) file.mkdirs()
        val out = File(file, "rec_${System.currentTimeMillis()}.mp4")
        outputFile = out
        // Also publish to MediaStore (Movies) so the user can FIND the video in
        // Gallery/Files and share it. Fires on stop via publishToMediaStore().
        pendingPublish = out

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(4_000_000)
        r.setAudioEncodingBitRate(128_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()

        val density = resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "FreeSongRecorder", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        recorder = r

        stopNotification()
            .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, it) }
    }

    private fun stopNotification(): Notification {
        val stopIntent = Intent(this, RecorderService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        // Content tap opens the app — it must NOT stop the recording.
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Free Song Recorder")
            .setContentText("Recording in progress — use the Stop action to finish")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
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
        cleanup()
        super.onDestroy()
    }

    /** Release all recording resources; safe to call anytime. */
    private fun cleanup() {
        stopMedia()
    }

    fun lastRecording(): File? = outputFile

    /** Make the recording visible in Gallery/Files (Movies) so the user can find it. */
    private fun publishToMediaStore(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FreeSongRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(file.readBytes())
                out.flush()
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {
            // Publishing is best-effort; the file still exists in app storage.
        }
    }

    private fun Notification(context: Context, title: String, text: String): Notification =
        android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()

    companion object {
        const val ACTION_START = "com.freesongfromreel.recorder.START"
        const val ACTION_STOP = "com.freesongfromreel.recorder.STOP"
        const val ACTION_RECORDING_STOPPED = "com.freesongfromreel.recorder.STOPPED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FILE = "file"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
    }
}