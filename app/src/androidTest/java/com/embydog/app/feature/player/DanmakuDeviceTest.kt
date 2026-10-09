package com.embydog.app.feature.player

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.danmaku.*
import com.embydog.app.core.media.PlaybackSnapshot
import com.embydog.app.debug.PlayerTestActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class DanmakuDeviceTest {
    @Test fun portraitPanelCanSwitchTabsAndChooseLibrary() = panel(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "portrait", 1f, 1f)
    @Test fun smallLandscapePanelRemainsReachable() = panel(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "landscape", 2f, 1f)
    @Test fun largeFontPanelRemainsReachable() = panel(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "large-font", 1f, 1.5f)

    private fun panel(orientation: Int, name: String, densityScale: Float, fontScale: Float) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        val chosen = AtomicLong(0)
        val candidate = DanmakuEpisode(42, "测试动画", "第 1 集")
        val state = mutableStateOf(DanmakuUiState(configured = true, selected = candidate, query = "测试动画", results = listOf(candidate)))
        try {
            instrument.runOnMainSync { activity.requestedOrientation = orientation }
            SystemClock.sleep(500)
            instrument.runOnMainSync { activity.setContentView(ComposeView(activity).apply { setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * densityScale, fontScale)) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        DanmakuSettingsPanel(state.value, { state.value = state.value.copy(display = it) }, { _, _ -> },
                            { chosen.set(it?.id ?: 0) }, {}, {})
                    }
                }
            } }) }
            instrument.waitForIdleSync()
            val close = waitFor("关闭弹幕设置")
            val bounds = Rect().also(close::getBoundsInScreen)
            assertTrue("Close button must fit: $bounds", bounds.top >= 0 && bounds.bottom <= activity.resources.displayMetrics.heightPixels &&
                bounds.left >= 0 && bounds.right <= activity.resources.displayMetrics.widthPixels)
            waitFor("不透明度")
            SystemClock.sleep(500) // The accessibility tree may precede the dialog's first rendered frame.
            screenshot("danmaku-settings-$name")
            click(waitFor("匹配弹幕"))
            waitFor("搜索")
            // The list can be below the viewport on short landscape devices. Scroll to it.
            var candidateNode = find("✓ 测试动画")
            repeat(6) {
                if (candidateNode == null || !candidateNode!!.isVisibleToUser) {
                    val root = instrument.uiAutomation.windows.mapNotNull { it.root }.flatMap { descendants(it) }
                    root.firstOrNull { it.isScrollable && it.className?.toString() != "android.widget.EditText" &&
                        it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(300) // Wait for LazyColumn's accessibility scroll animation to finish.
                    instrument.waitForIdleSync(); candidateNode = find("✓ 测试动画")
                }
            }
            click(candidateNode ?: error("Missing candidate"))
            instrument.waitForIdleSync()
            assertEquals(42L, chosen.get())
        } finally { finish(activity) }
    }

    @Test fun overlayDrawsAboveSurfaceViewWithoutConsumingVideoTaps() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = launch()
        val taps = AtomicInteger(0)
        try {
            instrument.runOnMainSync { activity.setContentView(ComposeView(activity).apply { setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { SurfaceView(it) }, modifier = Modifier.fillMaxSize())
                    DanmakuOverlay(DanmakuUiState(display = DanmakuDisplay(density = 3),
                        comments = listOf(DanmakuComment(0, DanmakuMode.TOP, 0xFFFFFF, "弹幕渲染测试"))),
                        PlaybackSnapshot(positionMs = 2000, isPlaying = false, isBuffering = false))
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { taps.incrementAndGet() } })
                }
            } }) }
            instrument.waitForIdleSync(); SystemClock.sleep(800)
            val image = instrument.uiAutomation.takeScreenshot() ?: error("No screenshot")
            val area = Rect().also { activity.findViewById<android.view.View>(android.R.id.content).getGlobalVisibleRect(it) }
            var brightPixels = 0
            for (y in area.top.coerceAtLeast(0) until minOf(area.top + 180, image.height))
                for (x in area.left.coerceAtLeast(0) until minOf(area.right, image.width)) {
                    val color = image.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 100 && android.graphics.Color.green(color) > 100 && android.graphics.Color.blue(color) > 100) brightPixels++
                }
            assertTrue("Danmaku must be visible over a native SurfaceView", brightPixels > 100 && brightPixels < area.width() * 50)
            File(instrument.targetContext.filesDir, "danmaku-overlay.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val x = area.centerX().toFloat(); val y = area.centerY().toFloat(); val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also { instrument.uiAutomation.injectInputEvent(it, true); it.recycle() }
            MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0).also { instrument.uiAutomation.injectInputEvent(it, true); it.recycle() }
            instrument.waitForIdleSync(); assertEquals(1, taps.get())
        } finally { finish(activity) }
    }

    private fun launch(): PlayerTestActivity {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlayerTestActivity
    }
    private fun finish(activity: PlayerTestActivity) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync { activity.finish() }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        instrument.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.takeScreenshot()?.let { image ->
            File(instrument.targetContext.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    private fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        add(root); repeat(root.childCount) { root.getChild(it)?.let { node -> addAll(descendants(node)) } }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).flatMap { descendants(it) }
            .firstOrNull { it.refresh() && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) { find(text)?.let { return it }; SystemClock.sleep(50) }
        error("Missing UI: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("Element must be clickable", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
