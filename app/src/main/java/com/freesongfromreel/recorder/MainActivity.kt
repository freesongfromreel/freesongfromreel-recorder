package com.freesongfromreel.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Main UI. Record button starts the projection-consent flow; Stop stops the
 * service; "Identify song" uploads the last recording to the Free Song from
 * Reel backend and shows the result + Spotify link. AdMob banner visible on
 * idle / editing / processing screens — hidden while recording.
 *
 * Funnel both ways:
 *   app  -> freesongfromreel.github.io (identify ANY video via URL/file)
 *   site -> Play Store page of this app
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var bannerTop: AdView
    private lateinit var banner: AdView
    private lateinit var loadingOverlay: android.view.View
    private lateinit var progressPct: TextView
    private var recording = false
    private var lastFile: File? = null

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pct = intent?.getIntExtra(RecorderService.EXTRA_PROGRESS, 0) ?: 0
            // Show a real percentage to the user while the MP4 is being muxed/saved.
            if (::progressPct.isInitialized) progressPct.text = "${pct.coerceIn(0, 100)}%"
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // ALWAYS reset the button to Record: this broadcast means recording
            // is over (whether saved or failed). Without this the button stays
            // stuck as "Stop & save" after a failed start, so the next tap shows
            // the consent popup again (looks like restarting).
            recording = false
            loadingOverlay.visibility = android.view.View.GONE
            findViewById<Button>(R.id.recordBtn).visibility = android.view.View.VISIBLE
            findViewById<Button>(R.id.identifyBtn).visibility = android.view.View.VISIBLE
            findViewById<Button>(R.id.siteBtn).visibility = android.view.View.VISIBLE
            setRecordingUi(false)
            lastFile = intent?.getStringExtra(RecorderService.EXTRA_FILE)?.let { File(it) }
                ?: lastFile()
            val audio = intent?.getStringExtra(RecorderService.EXTRA_AUDIO_SOURCE)
            status.text = when {
                ServiceState.lastError != null -> "Recording failed: ${ServiceState.lastError}"
                lastFile != null -> "Saved: ${lastFile?.name}" +
                    (if (audio != null) "\nAudio: $audio" else "")
                else -> "No recording found"
            }
        }
    }

    // RECORD_AUDIO first — only continue if granted (recording without mic throws).
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) notificationOrOverlayOrStart()
        }

    private fun notificationOrOverlayOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else overlayOrStart()
    }

    // POST_NOTIFICATIONS is optional — the recording works either way.
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { overlayOrStart() }

    // Overlay ("display over other apps") keeps the cast/Stop overlay usable and
    // stops the system from treating capture as dismissible — the fix most screen
    // recorders use to avoid the "looks recorded, then silently ended" state.
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startProjection() }

    private fun overlayOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            !Settings.canDrawOverlays(this)
        ) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else startProjection()
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                // Set shared state + UI SYNCHRONOUSLY. onResume fires right after
                // this callback and reads ServiceState; the service sets it only
                // async in onStartCommand, so without this the toggle flips back
                // to "Record" the instant the consent popups close.
                recording = true
                ServiceState.isRecording = true
                loadingOverlay.visibility = android.view.View.GONE
                setRecordingUi(true)
                val intent = Intent(this, RecorderService::class.java).apply {
                    action = RecorderService.ACTION_START
                    putExtra(RecorderService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(RecorderService.EXTRA_RESULT_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }

    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        bannerTop = findViewById(R.id.bannerTop)
        banner = findViewById(R.id.banner)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        progressPct = findViewById(R.id.progressPct)

        // Sync button/UI when the service stops on its own (notification Stop,
        // crash, etc.) — not just when WE tap Stop.
        // RECEIVER_NOT_EXPORTED required on API 33+ (targetSdk 34) or this throws.
        ContextCompat.registerReceiver(
            this, stopReceiver,
            IntentFilter(RecorderService.ACTION_RECORDING_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Progress updates for the "Extracting audio" overlay (%).
        ContextCompat.registerReceiver(
            this, progressReceiver,
            IntentFilter(RecorderService.ACTION_MIX_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        MobileAds.initialize(this) {}
        bannerTop.loadAd(AdRequest.Builder().build())
        banner.loadAd(AdRequest.Builder().build())

        findViewById<Button>(R.id.recordBtn).setOnClickListener {
            // Drive from the service's REAL state, not a stale local flag.
            val active = ServiceState.isRecording
            if (active) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                recording = false
                setRecordingUi(false)
                // Loading overlay = the "extracting audio" phase. Hidden when
                // the STOPPED broadcast arrives (service finished saving the MP4).
                loadingOverlay.visibility = android.view.View.VISIBLE
                findViewById<Button>(R.id.recordBtn).visibility = android.view.View.GONE
                findViewById<Button>(R.id.identifyBtn).visibility = android.view.View.GONE
                findViewById<Button>(R.id.siteBtn).visibility = android.view.View.GONE
                progressPct.text = "0%"
                status.text = "Extracting audio…"
            } else if (recording) {
                // Button still shows "Stop & save" but the service already ended
                // (system ended the projection — swipe of the cast notification,
                // screen-off, etc.). Tapping it must NOT re-open the recording
                // consent ("Start now" again) — settle back to Record.
                recording = false
                setRecordingUi(false)
                status.text = if (lastFile() != null) "Saved: ${lastFile()?.name}" else "No recording found"
            } else {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else notificationOrOverlayOrStart()
            }
        }

        findViewById<Button>(R.id.identifyBtn).setOnClickListener { identify() }

        findViewById<Button>(R.id.siteBtn).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://freesongfromreel.github.io/")))
        }
    }

    override fun onResume() {
        super.onResume()
        // NEVER down-sync here: right after the consent popups close, the
        // projection callback has set ServiceState=true but the service hasn't
        // started yet — reading the stale false flips the button back to
        // "Record" mid-start. Only up-sync (service recording → reflect it).
        if (ServiceState.isRecording) recording = true
        setRecordingUi(recording)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
    }

    private fun startProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun setRecordingUi(on: Boolean) {
        status.text = if (on) "Recording…" else "Idle — record a song, then tap Identify song"
        val btn = findViewById<Button>(R.id.recordBtn)
        btn.setText(if (on) R.string.record_stop else R.string.record)
        banner.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        bannerTop.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun recordingsDir(): File =
        File(getExternalFilesDir(null) ?: filesDir, "recordings").apply { mkdirs() }

    /** Latest known recording (most recent file), or null. */
    private fun lastFile(): File? =
        recordingsDir().listFiles()?.maxByOrNull { it.lastModified() }

    private fun identify() {
        val file = lastFile
            ?: run { status.text = "Record something first."; return }
        result.text = "Identifying…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val json = withContext(Dispatchers.IO) { upload(file) }
                val title = json.optString("title", "Unknown")
                val artist = json.optString("artist", "")
                val spotify = json.optString("spotify", "")
                result.text = buildString {
                    append("🎵 $title").append(if (artist.isNotEmpty()) "\n$artist" else "")
                    if (spotify.isNotEmpty()) append("\n$spotify")
                }
            } catch (e: Exception) {
                result.text = "Could not identify: ${e.message ?: "network error"}"
            }
        }
    }

    private fun upload(file: File): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("video/mp4".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://reel2song-backend.onrender.com/api/detect-file")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = JSONObject(text).optString("detail", "error ${resp.code}")
                throw IllegalStateException(detail)
            }
            return JSONObject(text)
        }
    }
}