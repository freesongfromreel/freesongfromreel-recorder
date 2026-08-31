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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import java.io.File

/**
 * Foreground MediaProjection screen recorder. Records the phone's OWN media
 * playback (the reel's song) + the screen into filesDir/recordings/<ts>.mp4.
 *
 * Audio + video muxing lives in [RecorderEngine] (MediaRecorder can't capture
 * app-internal audio). Audio falls back to the microphone on old devices or
 * when no capturable source exists — the UI reports which one was used.
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var engine: RecorderEngine? = null
    private var outputFile: File? = null
    private var pendingPublish: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Channel created here; typed FGS is deferred until AFTER getMediaProjection
        // in startRecordingInner (API 34 wants an active projection for the
        // mediaProjection type; created in startForegroundCompat).
        val ch = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
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

    /** Stop the engine + projection + service. Safe to call anytime. */
    private fun stopRecording() {
        ServiceState.isRecording = false
        // Finalize (drain encoders, close muxer, publish to Gallery) off the main
        // thread; broadcast only when the MP4 is actually saved so the Activity's
        // loading overlay covers the whole "mixing" phase.
        Thread {
            android.util.Log.i(TAG, "stopRecording: engine.stop() begin")
            val bytes = engine?.bytesRecorded ?: 0L
            try { engine?.stop() } catch (e: Exception) { android.util.Log.e(TAG, "engine.stop threw", e) }
            android.util.Log.i(TAG, "stopRecording: engine.stop() done")
            engine = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            val f = outputFile
            if (f != null && pendingPublish != null) {
                publishToMediaStore(f)
                pendingPublish = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            android.util.Log.i(TAG, "stopRecording: broadcasting STOPPED")
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED)
                .putExtra(EXTRA_FILE, f?.absolutePath)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSourceName)
                .putExtra(EXTRA_BYTES, bytes))
            stopSelf()
            android.util.Log.i(TAG, "stopRecording: done")
        }.start()
    }

    /** Relay finalize/mix progress (0-100) to the Activity so it can show % while saving. */
    private val progressRelay: (Int) -> Unit = { pct ->
        try {
            sendBroadcast(Intent(ACTION_MIX_PROGRESS).putExtra(EXTRA_PROGRESS, pct))
        } catch (_: Exception) {}
    }

    private val audioSourceName: String
        get() = engine?.audioSourceName ?: "Microphone"

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
            // Satisfy the startForegroundService 5s rule even on the failure path
            // (never started foreground → system would kill us with an exception).
            startForegroundCompat()
            stopForeground(STOP_FOREGROUND_REMOVE)
            Notification(this, "Recording failed", errMsg)
                .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID + 1, it) }
            // The Activity optimistically set UI to "Recording…" before starting
            // us — tell it to revert (and surface the error) instead of leaving
            // a desynced Stop button that does nothing.
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
            stopSelf()
        }
    }

    private fun startRecordingInner(resultCode: Int, data: Intent?) {
        if (data == null) { stopSelf(); return }

        // ORDER MATTERS (Android 14+): AOSPMediaProjectionManagerService.start()
        // fires inside the MediaProjection constructor — i.e. DURING getMediaProjection()
        // — and throws:
        //   "Media projections require a foreground service of type
        //   ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"
        // if the mediaProjection-typed FGS is not ALREADY running. So the typed FGS
        // MUST start BEFORE getMediaProjection(). (TargetSdk >= 29 enforces this.)
        val fgErr = startForegroundCompat()
        if (fgErr != null) {
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            throw IllegalStateException("Foreground service (mediaProjection): $fgErr")
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        projection = proj

        val file = File(getExternalFilesDir(null) ?: filesDir, "recordings")
        if (!file.exists()) file.mkdirs()
        val out = File(file, "rec_${System.currentTimeMillis()}.wav")
        outputFile = out
        // Also publish to MediaStore (Movies) so the user can FIND the video in
        // Gallery/Files and share it. Fires on stop via publishToMediaStore().
        pendingPublish = out

        val e = RecorderEngine(this, proj, out)
        engine = e
        e.listener = object : RecorderEngine.Listener {
            override fun onError(msg: String) {
                // Async encoder failure mid-recording (not the sync start path).
                ServiceState.isRecording = false
                ServiceState.lastError = msg
                Notification(this@RecorderService, "Recording failed", msg)
                    .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID + 1, it) }
                stopRecording()
            }

            override fun onStopped() {
                // User/system ended capture (swiped the cast notification) — just
                // finalize what we have, no error.
                ServiceState.lastError = null
                stopRecording()
            }

            override fun onStopProgress(percent: Int) {
                progressRelay(percent)
            }
        }
        e.start()

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
            .setOngoing(true) // non-dismissible: swiping it away kills capture silently
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .build()
    }

    private var fgStarted = false

    /**
     * Start this service as foreground with the mediaProjection type. Returns
     * null on success, or an error message on failure.
     *
     * CRITICAL: on API 34+, if the typed startForeground fails we must NOT fall
     * back to a plain (untyped) startForeground — a mediaProjection-typed FGS is
     * REQUIRED for createVirtualDisplay ("Media projections require a foreground
     * service of type ...MEDIA_PROJECTION"). Falling back masks the problem and
     * produces a phantom recording that can never actually capture. Fail loudly.
     */
    private fun startForegroundCompat(): String? {
        if (fgStarted) return null
        val n = Notification(this, "Free Song Recorder", "Ready to record")
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            fgStarted = true
            null
        } catch (e: Exception) {
            e.message ?: "Could not start foreground service (mediaProjection type)"
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    /** Release all recording resources; safe to call anytime. */
    private fun cleanup() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        pendingPublish?.let { publishToMediaStore(it); pendingPublish = null }
    }

    /** Make the recording visible in Gallery/Files (Music) so the user can find it. */
    private fun publishToMediaStore(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/FreeSongRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(file.readBytes())
                out.flush()
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
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
        private const val TAG = "RecorderService"
        const val ACTION_START = "com.freesongfromreel.recorder.START"
        const val ACTION_STOP = "com.freesongfromreel.recorder.STOP"
        const val ACTION_RECORDING_STOPPED = "com.freesongfromreel.recorder.STOPPED"
        const val ACTION_MIX_PROGRESS = "com.freesongfromreel.recorder.MIX_PROGRESS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FILE = "file"
        const val EXTRA_AUDIO_SOURCE = "audio_source"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_BYTES = "bytes"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
    }
}
