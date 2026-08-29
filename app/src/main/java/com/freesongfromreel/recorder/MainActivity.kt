package com.freesongfromreel.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var banner: AdView
    private var recording = false
    private var lastFile: File? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                val intent = Intent(this, RecorderService::class.java).apply {
                    action = RecorderService.ACTION_START
                    putExtra(RecorderService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(RecorderService.EXTRA_RESULT_DATA, res.data)
                }
                ContextCompat.startForegroundService(this, intent)
                recording = true
                setRecordingUi(true)
            }
        }

    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        banner = findViewById(R.id.banner)

        MobileAds.initialize(this) {}
        banner.loadAd(AdRequest.Builder().build())

        findViewById<Button>(R.id.recordBtn).setOnClickListener {
            // Drive from the service's real state, not a stale local flag.
            val active = ServiceState.isRecording
            if (!active) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else startProjection()
            } else {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                recording = false
                setRecordingUi(false)
                lastFile = lastFile()
                status.text = if (lastFile != null) "Saved: ${lastFile?.name}" else "No recording found"
            }
        }

        findViewById<Button>(R.id.identifyBtn).setOnClickListener { identify() }

        findViewById<Button>(R.id.siteBtn).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://freesongfromreel.github.io/")))
        }
    }

    private fun startProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun setRecordingUi(on: Boolean) {
        status.text = if (on) "Recording…" else "Idle — record a video, then tap Identify song"
        val btn = findViewById<Button>(R.id.recordBtn)
        btn.setText(if (on) R.string.record_stop else R.string.record)
        banner.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
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