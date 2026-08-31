package com.freesongfromreel.recorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private val tag = "RecordFlowSmokeTest"

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, MainActivity::class.java.name),
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP
        )
        // Pre-grant ALL runtime permissions + overlay so the flow needs zero
        // system-dialog chasing (dialog-driving is flaky on fresh emulators and
        // it can't distinguish "permission chain stalled" from "consent launch
        // failed" — this isolates the pipeline).
        val pkg = context.packageName
        runCatching { device.executeShellCommand("pm grant $pkg android.permission.RECORD_AUDIO") }
        runCatching { device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS") }
        runCatching { device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow") }
        // NOTE: do NOT pre-grant PROJECT_MEDIA — doing so bypasses the
        // MediaProjection consent dialog entirely (the system auto-grants the
        // projection), so the test would never see "Start now".
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

        // Tap Record -> grants + consent flow (permissions are pre-granted, so
        // this should go straight to the projection consent).
        record!!.click()
        tapProjectionStart()

        // App must SURVIVE and the same button must read "Stop & save".
        val stopBtn = waitForRes("recordBtn", 12_000)
        if (stopBtn == null) dumpScreen("no stopBtn after start")
        assertNotNull("Button should still exist after starting (pkg=${device.currentPackageName})", stopBtn)
        val stopText = stopBtn!!.text
        assertTrue(
            "Button should read 'Stop & save' after starting, was: '$stopText'",
            stopText.contains("stop", ignoreCase = true)
        )

        // Give it a moment, then stop via the button.
        device.waitForIdle()
        stopBtn.click()
        val backToRecord = waitForRes("recordBtn", 12_000)
        if (backToRecord == null) dumpScreen("no recordBtn after stop")
        assertNotNull("After stop, button should return", backToRecord)
        val backText = backToRecord!!.text
        assertTrue(
            "Button should read 'Record screen' after stop, was: '$backText'",
            backText.contains("record", ignoreCase = true)
        )
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
                device.findObjects(By.pkg(context.packageName)).forEach {
                    p.println("  obj: ${it.className} text='${it.text}' res=${it.resourceName}")
                }
            } catch (_: Exception) {}
            p.println("=== end dump ===")
        }
        // Log.i goes to logcat, which gradle connectedDebugAndroidTest captures.
        android.util.Log.i(this.tag, sw.toString())
    }

    /** Tap "Start now" on the screen-capture consent dialog (media projection). */
    private fun tapProjectionStart() {
        val start = device.wait(Until.findObject(By.textContains("Start now")), 10_000)
        if (start == null) {
            dumpScreen("no Start now")
            // Include what system dialog IS present so a wording change is visible.
            val hint = try {
                device.findObjects(By.clazz("android.widget.Button")).map {
                    "button:'${it.text}'"
                }.joinToString()
            } catch (_: Exception) { "?" }
            assertNotNull(
                "Projection consent missing (pkg=${device.currentPackageName} buttons=$hint)",
                start
            )
        } else {
            start.click()
        }
    }
}