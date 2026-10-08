package com.embydog.app.feature.player

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.debug.PlayerTestActivity
import com.embydog.app.core.media.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/** Real pointer input, accessibility bounds and screenshots; no network/video fixture is needed. */
class PlayerControlsDeviceTest {
    @Test fun landscapeControlsAreLargeAndSliderCommitsWithoutLeakingTapsToVideo() = exercise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "landscape")
    @Test fun portraitControlsRemainReachableAndSliderCommits() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "portrait")
    @Test fun smallPortraitControlsRemainReachable() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "small-portrait", 2f)
    @Test fun shortLandscapeControlsRemainReachable() = exercise(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "short-landscape", 2f)
    @Test fun largeFontPortraitControlsRemainReachable() = exercise(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "large-font-portrait", 1.5f, 1.5f)

    private fun exercise(orientation: Int, name: String, densityScale: Float = 1f, fontScale: Float = 1f) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        // Observe window replacement before launching the host. On API 32 the cached
        // active-window id can disappear after the previous rotated activity closes.
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrument.targetContext
        val activity = instrument.startActivitySync(Intent(context, PlayerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val original = activity.requestedOrientation
        val sought = AtomicLong(-1); val previewed = AtomicLong(-1); val videoTaps = AtomicLong()
        val snapshot = PlaybackSnapshot(positionMs = 600_000, durationMs = 7_200_000, isPlaying = true,
            isBuffering = false, downloadBytesPerSecond = 1_500_000)
        val controlsVisible = mutableStateOf(true)
        val engine = object : PlaybackEngine {
            override val kind = PlaybackEngineKind.MPV
            override val snapshot = MutableStateFlow(snapshot)
            override fun createVideoView(context: Context): View = View(context)
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun setSpeed(speed: Float) = Unit
            override fun selectTrack(kind: PlaybackTrackKind, id: String?) = Unit
            override fun refresh() = Unit
            override fun release() = Unit
        }
        try {
            instrument.runOnMainSync { activity.requestedOrientation = orientation }
            val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) 2 else 1
            val orientationDeadline = SystemClock.uptimeMillis() + 5000
            while (activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < orientationDeadline) SystemClock.sleep(50)
            instrument.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density * densityScale, fontScale)) {
                        MaterialTheme(colorScheme = darkColorScheme()) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF626976))) {
                                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { videoTaps.incrementAndGet() } })
                                PlayerControls(state = PlayerUiState(isLoading = false, engine = engine, playback = snapshot),
                                    visible = controlsVisible.value, automaticRotation = false, onRotate = {}, onAutomaticRotation = {}, onEpisodes = {},
                                    onBusyChange = {}, onBack = {}, onInteraction = {}, onToggle = {}, onSeek = { sought.set(it) },
                                    onPreviewSeek = { previewed.set(it) }, onCancelPreview = {}, onEngine = {}, onSpeed = {}, onTrack = { _, _ -> })
                            }
                        }
                        }
                    }
                })
            }
            instrument.waitForIdleSync()
            val slider = waitForDescription("播放进度")
            val play = waitForDescription("暂停")
            val playBounds = Rect().also(play::getBoundsInScreen)
            val density = activity.resources.displayMetrics.density * densityScale
            assertTrue("Playback button bounds=${playBounds}, density=$density must retain a 48dp touch target", playBounds.width() >= 48 * density)
            assertTrue("Playback button must fit within the screen", playBounds.left >= 0 && playBounds.right <= activity.resources.displayMetrics.widthPixels)
            assertEquals("Playback button must stay horizontally centered", activity.resources.displayMetrics.widthPixels / 2f,
                playBounds.exactCenterX(), 2f)
            listOf("字幕", "音轨", "播放速度", "手动切换横屏或竖屏").forEach { description ->
                val control = waitForDescription(description)
                val controlBounds = Rect().also(control::getBoundsInScreen)
                assertTrue("$description must fit within the screen: $controlBounds", controlBounds.left >= 0 &&
                    controlBounds.top >= 0 && controlBounds.right <= activity.resources.displayMetrics.widthPixels &&
                    controlBounds.bottom <= activity.resources.displayMetrics.heightPixels)
            }
            val subtitleBounds = Rect().also(waitForDescription("字幕")::getBoundsInScreen)
            val audioBounds = Rect().also(waitForDescription("音轨")::getBoundsInScreen)
            val speedBounds = Rect().also(waitForDescription("播放速度")::getBoundsInScreen)
            assertTrue("Track controls must be grouped on the right", subtitleBounds.centerX() > activity.resources.displayMetrics.widthPixels / 2)
            assertTrue("Subtitle/audio/speed must be tightly grouped", audioBounds.left - subtitleBounds.right <= 30 * density &&
                speedBounds.left - audioBounds.right <= 30 * density)
            assertTrue("Download speed must appear with the player controls", hasText("MiB/s"))
            val bounds = Rect().also(slider::getBoundsInScreen)
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, x: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, bounds.exactCenterY(), 0)
                instrument.sendPointerSync(event); event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, bounds.left + bounds.width() * 0.2f)
            repeat(10) {
                SystemClock.sleep(25)
                send(MotionEvent.ACTION_MOVE, bounds.left + bounds.width() * (0.2f + 0.06f * (it + 1)))
            }
            send(MotionEvent.ACTION_UP, bounds.left + bounds.width() * 0.8f)
            instrument.waitForIdleSync()
            assertTrue("Drag must show preview positions", previewed.get() > 0)
            assertTrue("Release must commit a seek", sought.get() > 4_000_000)
            assertEquals("Slider gestures must not toggle the video overlay", 0L, videoTaps.get())
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "player-controls-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrument.runOnMainSync { controlsVisible.value = false }
            instrument.waitForIdleSync()
            val hiddenDeadline = SystemClock.uptimeMillis() + 3000
            while (hasText("MiB/s") && SystemClock.uptimeMillis() < hiddenDeadline) SystemClock.sleep(50)
            assertFalse("Download speed must disappear with the player controls", hasText("MiB/s"))
        } finally {
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "player-controls-$name-final.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrument.runOnMainSync { activity.requestedOrientation = original; activity.finish() }
            // Await complete destruction before the next case creates an orientation-specific window.
            val destroyDeadline = SystemClock.uptimeMillis() + 5000
            while (!activity.isDestroyed && SystemClock.uptimeMillis() < destroyDeadline) SystemClock.sleep(50)
            instrument.waitForIdleSync()
        }
    }

    private fun hasText(text: String): Boolean {
        fun find(node: AccessibilityNodeInfo): Boolean {
            if (!node.refresh()) return false
            if (node.text?.contains(text) == true) return true
            repeat(node.childCount) { node.getChild(it)?.let { child -> if (find(child)) return true } }
            return false
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.windows.mapNotNull { it.root }.filter { it.packageName == "com.embydog.app" }.any(::find)
    }

    private fun waitForDescription(description: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.contentDescription?.toString()?.contains(description) == true) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let { child -> find(child)?.let { return it } } }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            roots.filter { it.packageName == "com.embydog.app" }.forEach { root ->
                find(root)?.let { found -> return found }
            }
            SystemClock.sleep(50)
        }
        val nodes = StringBuilder()
        fun dump(node: AccessibilityNodeInfo, depth: Int = 0) {
            val bounds = Rect().also(node::getBoundsInScreen)
            nodes.appendLine("${"  ".repeat(depth)}${node.className}: ${node.contentDescription} text=${node.text} bounds=$bounds children=${node.childCount}")
            repeat(node.childCount) { node.getChild(it)?.let { child -> dump(child, depth + 1) } }
        }
        automation.rootInActiveWindow?.let { dump(it) }
        automation.windows.forEach { window ->
            nodes.appendLine("window=${window.id}, active=${window.isActive}, focused=${window.isFocused}")
            window.root?.let { dump(it) }
        }
        error("Missing accessibility control: $description\n$nodes")
    }
}
