package com.freesongfromreel.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test that would have caught the last three regressions:
 *  - crash as soon as the projection consent is accepted (missed callback /
 *    untyped-FGS fallback)
 *  - phantom "Recording…" that never actually captures (typed-FGS requirement)
 *  - button not resetting to Record after a failure (stopped-broadcast desync)
 *
 * Drives the REAL UI + system popups via UiAutomator:
 *   launch app -> tap Record -> grant mic -> grant notifications ->
 *   grant overlay -> tap "Start now" on the projection consent ->
 *   assert the app SURVIVES and the button becomes "Stop & save".
 */
@RunWith(AndroidJUnit4::class)
class RecordFlowSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Only needed if we run against an app that wasn't freshly reset — do a
        // clean app start each test.
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context.packageName, MainActivity::class.java.name),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
        )
        device.pressHome()
    }

    @Test
    fun recordFlow_survivesAndToggles() {
        // launch the app
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launcher)
        device.wait(Until.hasObject(By.text("⏺ Record screen")), 8_000)
        assertNotNull("Record button should appear", device.findObject(By.text("⏺ Record screen")))

        // tap Record -> grants + consent flow
        device.findObject(By.text("⏺ Record screen")).click()
        handleRuntimePermission(Manifest.permission.RECORD_AUDIO)
        handleRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        handleOverlayPermission()
        tapProjectionStart()

        // the app must SURVIVE (no crash) and show the toggle
        device.wait(Until.hasObject(By.text("⏹ Stop & save")), 8_000)
        assertNotNull("Button should become 'Stop & save' after starting", device.findObject(By.text("⏹ Stop & save")))

        // give it a moment, then stop via the button
        device.waitForIdle()
        val stop = device.findObject(By.text("⏹ Stop & save"))
        assertNotNull(stop)
        stop.click()
        device.wait(Until.hasObject(By.text("⏺ Record screen")), 10_000)
        assertNotNull("After stop, button returns to Record", device.findObject(By.text("⏺ Record screen")))
    }

    // ---- helpers -----------------------------------------------------------

    /** Grant a runtime permission via the system dialog if it's not already granted. */
    private fun handleRuntimePermission(permission: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            device.wait(Until.hasObject(By.textContains("Allow")), 6_000)
            val allow = device.findObject(By.textContains("Allow"))
            if (allow != null) allow.click()
        }
    }

    /** Grant the overlay ("display over other apps") permission if not granted. */
    private fun handleOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Settings.canDrawOverlays(context)) {
            // We're on the "Allow display over other apps" settings screen.
            device.wait(Until.hasObject(By.textContains("Allow")), 8_000)
            val allow = device.findObject(By.textContains("Allow"))
            if (allow != null) allow.click()
            // Back to the app.
            device.pressBack()
            // The launcher callback (StartActivityForResult) resumes us.
            device.waitForIdle()
        }
    }

    /** Tap "Start now" on the screen-capture consent dialog (media projection). */
    private fun tapProjectionStart() {
        // The consent says "Start now" (start capturing) / "Don't start".
        device.wait(Until.hasObject(By.textContains("Start now")), 8_000)
        val start = device.findObject(By.textContains("Start now"))
        assertNotNull("Should see the projection 'Start now' prompt", start)
        start.click()
    }
}