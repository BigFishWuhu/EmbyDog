package com.embydog.app.feature.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.debug.PlayerTestActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SleepTimerDialogDeviceTest {
    @Test fun compactPortraitDialogStartsTimer() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "portrait", 1f)
    @Test fun compactLandscapeDialogStartsTimer() = exercise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "landscape", 2f)
    @Test fun largeFontDialogRemainsUsable() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "large-font", 1.5f, 1.5f)

    private fun exercise(orientation: Int, name: String, densityScale: Float, fontScale: Float = 1f) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val selected = AtomicInteger(0)
        try {
            instrument.runOnMainSync { activity.requestedOrientation = orientation }
            SystemClock.sleep(500)
            instrument.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply { setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density * densityScale, fontScale)) {
                        MaterialTheme { SleepTimerDialog(null, { minutes, action ->
                            assertEquals(SleepTimerAction.PAUSE, action)
                            selected.set(minutes)
                        }, {}, {}) }
                    }
                } })
            }
            instrument.waitForIdleSync()
            val start = waitForText("开始计时")
            val bounds = Rect().also(start::getBoundsInScreen)
            assertTrue("Start button must fit on screen: $bounds", bounds.top >= 0 && bounds.bottom <= activity.resources.displayMetrics.heightPixels &&
                bounds.left >= 0 && bounds.right <= activity.resources.displayMetrics.widthPixels)
            SystemClock.sleep(400) // Accessibility can update before the dialog's first rendered frame.
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(instrument.targetContext.filesDir, "sleep-dialog-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            var clickable: AccessibilityNodeInfo? = start
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue("Timer start must be clickable", clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrument.waitForIdleSync()
            assertEquals(30, selected.get())
        } finally {
            instrument.runOnMainSync { activity.finish() }
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            instrument.waitForIdleSync()
        }
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.text?.toString() == text) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { find(it)?.let { found -> return found } } }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            roots.forEach { find(it)?.let { found -> return found } }
            SystemClock.sleep(50)
        }
        error("Missing dialog text: $text")
    }
}
