package com.embydog.app.feature.offline

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.core.offline.DownloadStatus
import com.embydog.app.core.offline.OfflineRecord
import com.embydog.app.core.session.privateCacheKey
import com.embydog.app.debug.PlayerTestActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OfflineScreenDeviceTest {
    @Test fun compactPortraitCanPlayWithoutConnectingAndDeletionNeedsConfirmation() = screen(false, 1.4f, 1f, "compact-portrait")
    @Test fun compactLandscapeKeepsOfflinePlaybackReachable() = screen(true, 1f, 1f, "compact-landscape")
    @Test fun largerTextKeepsOfflineControlsReachable() = screen(false, 1f, 1.5f, "large-text")

    private fun screen(landscape: Boolean, scale: Float, font: Float, name: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val plays = AtomicInteger(); val deletes = AtomicInteger()
        val record = OfflineRecord(privateCacheKey("offline-ui-fixture"), "profile", "离线服务器 · 用户", "http://127.0.0.1:1/", "fixture", "item",
            "服务器不可用时仍能播放的测试影片", "mkv", 40_000, PlaybackSelection(), emptyList(), status = DownloadStatus.COMPLETED, bytes = 1024, totalBytes = 1024)
        try {
            instrument.runOnMainSync { activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            SystemClock.sleep(400)
            instrument.runOnMainSync { activity.setContentView(ComposeView(activity).apply { setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, font)) {
                    MaterialTheme { OfflineContent(OfflineUiState(listOf(record), listOf("profile" to record.profileName), "profile"), null, {},
                        { assertEquals(record.id, it); plays.incrementAndGet() }, {}, {}, { deletes.incrementAndGet() }, {}, {}) }
                }
            } }) }
            instrument.waitForIdleSync()
            var play = find("播放")
            if (play == null) {
                findScrollable()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(400); play = find("播放")
            }
            val node = requireNotNull(play) { "Offline playback button missing" }
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue("Play must fit within the screen: $bounds", bounds.left >= 0 && bounds.right <= activity.resources.displayMetrics.widthPixels && bounds.top >= 0 && bounds.bottom <= activity.resources.displayMetrics.heightPixels)
            click(node); SystemClock.sleep(200); assertEquals(1, plays.get())
            click(requireNotNull(find("删除缓存"))); SystemClock.sleep(250)
            assertEquals(0, deletes.get()); click(requireNotNull(find("取消"))); SystemClock.sleep(250)
            click(requireNotNull(find("删除缓存"))); SystemClock.sleep(250)
            click(requireNotNull(find("删除"))); SystemClock.sleep(250); assertEquals(1, deletes.get())
            val folder = File(activity.getExternalFilesDir(null), "verification/offline").apply { mkdirs() }
            instrument.uiAutomation.takeScreenshot().useBitmap { bitmap -> File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        } finally { instrument.runOnMainSync { activity.finish() } }
    }

    private fun find(text: String): AccessibilityNodeInfo? = nodes().firstOrNull { (it.text?.toString() == text || it.contentDescription?.toString() == text) && it.isVisibleToUser }
    private fun findScrollable() = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }
    private fun click(node: AccessibilityNodeInfo) {
        var candidate: AccessibilityNodeInfo? = node
        repeat(6) {
            val current = candidate ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = current.parent
        }
        fail("No clickable parent for ${node.text ?: node.contentDescription}")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            if (!node.refresh()) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        }
        return automation.windows.flatMap { it.root?.let(::walk).orEmpty() }
    }
    private inline fun android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}
