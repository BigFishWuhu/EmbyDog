package com.embydog.app.feature.media

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.model.MediaItem
import com.embydog.app.debug.PlayerTestActivity
import com.embydog.app.ui.theme.EmbyDogTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Isolated home shelves: no login, server requests or changes to real playback history. */
class HomeLayoutDeviceTest {
    @Test fun smallPhoneKeepsTwoReadablePosters() = screen(320f, 1f, false, "small-phone")
    @Test fun normalPhoneUsesComfortablePosters() = screen(390f, 1f, false, "normal-phone")
    @Test fun landscapeCapsPosterSize() = screen(700f, 1f, true, "landscape")
    @Test fun largeTextKeepsTitlesAndCardsReachable() = screen(360f, 1.5f, false, "large-text")

    private fun screen(widthDp: Float, fontScale: Float, landscape: Boolean, name: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val automation = instrument.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val clicks = AtomicInteger()
        val movies = listOf("星海漫游：一次很长的旅程", "山间来信", "城市故事", "远方").mapIndexed { index, title ->
            fixture("movie-$index", title, "Movie", "2026")
        }
        val episodes = listOf(fixture("episode", "漫游日记 · 第一集", "Episode", "第 1 季 · 第 1 集"))
        try {
            instrument.runOnMainSync {
                activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            SystemClock.sleep(450)
            val density = activity.resources.displayMetrics.widthPixels / widthDp
            instrument.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply { setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        EmbyDogTheme(dynamicColor = false) {
                            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("EmbyDog", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
                                HomeMediaRow("最近添加", movies, "", { clicks.incrementAndGet() })
                                HomeMediaRow("继续观看", episodes, "", {})
                            }
                        }
                    }
                } })
            }
            instrument.waitForIdleSync()
            SystemClock.sleep(400)
            val first = requireNotNull(card(movies.first().name)) { "First poster not accessible" }
            val firstBounds = Rect().also(first::getBoundsInScreen)
            assertEquals(homePosterWidth(widthDp, fontScale), firstBounds.width() / density, 1.5f)
            assertTrue("First card must fit: $firstBounds", firstBounds.left >= 0 && firstBounds.right <= activity.resources.displayMetrics.widthPixels)
            val secondBounds = Rect().also(requireNotNull(card(movies[1].name))::getBoundsInScreen)
            assertTrue("Second card must remain fully visible: $secondBounds", secondBounds.right <= activity.resources.displayMetrics.widthPixels)
            assertEquals("Title lengths must not change card height", firstBounds.bottom, secondBounds.bottom)
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(100)
            assertEquals(1, clicks.get())
            val folder = File(activity.getExternalFilesDir(null), "verification/home").apply { mkdirs() }
            val bitmap = requireNotNull(automation.takeScreenshot())
            try {
                File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        } finally { instrument.runOnMainSync { activity.finish() } }
    }

    private fun card(title: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && (node.text?.toString()?.contains(title) == true || node.contentDescription?.toString() == title)) {
                var current: AccessibilityNodeInfo? = node
                repeat(6) {
                    val candidate = current ?: return@repeat
                    if (candidate.isClickable) return candidate
                    current = candidate.parent
                }
            }
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.windows.firstNotNullOfOrNull { it.root?.let(::walk) }
    }

    private fun fixture(id: String, title: String, type: String, subtitle: String) =
        MediaItem(id, title, type, "", 2026, 90, null, null, null, subtitle, 0)
}
