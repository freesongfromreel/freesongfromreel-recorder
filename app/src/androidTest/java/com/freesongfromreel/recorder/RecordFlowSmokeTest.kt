package com.freesongfromreel.recorder

import android.Manifest
import android.content.ComponentName
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
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PrintWriter
import java.io.StringWriter

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
 *
 * Selectors use RESOURCE IDs (By.res) where possible — matching button text
 * that starts with an emoji (⏺) via By.text is unreliable in UiAutomator.
 */
@RunWith(AndroidJUnit4::class)
class RecordFlowSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, MainActivity::class.java.name),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
        )
        device.pressHome()
    }

    @Test
    fun recordFlow_survivesAndToggles() {
        // Launch the app.
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launcher)

        // The Record button (resource id, emoji-immune).
        val record = waitForRes("recordBtn", 12_000)
        if (record == null) dumpScreen("no recordBtn")
        assertNotNull("Record button should appear (is app foreground? pkg=${device.currentPackageName})", record)

        // Tap Record -> grants + consent flow.
        record.click()
        handleRuntimePermission(Manifest.permission.RECORD_AUDIO)
        handleRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
        handleOverlayPermission()
        tapProjectionStart()

        // App must SURVIVE and the same button must read "Stop & save".
        val stopBtn = waitForRes("recordBtn", 12_000)
        if (stopBtn == null) dumpScreen("no stopBtn after start")
        assertNotNull("Button should still exist after starting (pkg=${device.currentPackageName})", stopBtn)
        val stopText = stopBtn.text
        assertTrue(
            "Button should read 'Stop & save' after starting, was: '$stopText'",
            stopText.contains("Stop")
        )

        // Give it a moment, then stop via the button.
        device.waitForIdle()
        stopBtn.click()
        val backToRecord = waitForRes("recordBtn", 12_000)
        if (backToRecord == null) dumpScreen("no recordBtn after stop")
        assertNotNull("After stop, button should return", backToRecord)
        val backText = backToRecord.text
        assertTrue("Button should read 'Record screen' after stop, was: '$backText'", backText.contains("Record"))
    }

    // ---- helpers -----------------------------------------------------------

    private fun waitForRes(resId: String, timeoutMs: Long): UiObject2? {
        return device.wait(Until.hasObject(By.res(context.packageName, resId)), timeoutMs)
            ?.let { device.findObject(By.res(context.packageName, resId)) }
    }

    /** Dump current package + visible text/resource ids to help diagnose failures. */
    private fun dumpScreen(tag: String) {
        val sw = StringWriter()
        PrintWriter(sw).use { p ->
            p.println("=== dumpScreen[$tag] pkg=${device.currentPackageName} ===")
            try {
                device.findObjects(By.pkg(context.packageName)).forEach { p.println("  obj: ${it.className} ${it.text}") }
            } catch (_: Exception) {}
            p.println("=== end dump ===")
        }
        println(sw.toString())
    }

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
            device.waitForIdle()
        }
    }

    /** Tap "Start now" on the screen-capture consent dialog (media projection). */
    private fun tapProjectionStart() {
        device.wait(Until.hasObject(By.textContains("Start now")), 8_000)
        val start = device.findObject(By.textContains("Start now"))
        if (start == null) dumpScreen("no Start now")
        assertNotNull("Should see the projection 'Start now' prompt", start)
        start.click()
    }
}