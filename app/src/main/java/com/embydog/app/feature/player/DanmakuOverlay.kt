package com.embydog.app.feature.player

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.embydog.app.core.danmaku.DanmakuTimeline
import com.embydog.app.core.media.PlaybackSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

@Composable
fun DanmakuOverlay(state: DanmakuUiState, playback: PlaybackSnapshot, modifier: Modifier = Modifier) {
    if (!state.display.enabled || state.comments.isEmpty() || playback.hasEnded) return
    val display = state.display
    val layoutDisplay = display.copy(opacity = 1f, offsetMs = 0, enabled = true, showColor = true)
    val density = LocalDensity.current
    val textSize = with(density) { display.textSizeSp.sp.toPx() }
    val topInset = with(density) { 12.sp.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val paint = remember(textSize) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
    } }
    val rowHeight = textSize * 1.4f
    val regionHeight = viewport.height * minOf(display.area, if (display.protectSubtitles) 0.80f else 1f)
    val laneCount = ((regionHeight - topInset) / rowHeight).toInt().coerceIn(0, 32)
    var timeline by remember { mutableStateOf(DanmakuTimeline(emptyList(), 8000)) }
    LaunchedEffect(state.comments, layoutDisplay, viewport.width, laneCount, textSize) {
        timeline = DanmakuTimeline(emptyList(), 8000)
        timeline = withContext(Dispatchers.Default) {
            val measuringPaint = Paint(paint)
            DanmakuTimeline.build(state.comments, layoutDisplay, viewport.width.toFloat(), laneCount, measuringPaint::measureText)
        }
    }
    // Engine snapshots arrive every 500 ms; interpolate only inside that bounded interval.
    // Re-anchor on every snapshot, including seeks and pause/buffering transitions.
    val sample = remember(playback.positionMs, playback.isPlaying, playback.isBuffering, playback.speed) {
        playback.positionMs to SystemClock.elapsedRealtime()
    }
    var position by remember { mutableLongStateOf(playback.positionMs) }
    LaunchedEffect(sample, playback.isPlaying, playback.isBuffering, playback.speed) {
        position = sample.first
        if (playback.isPlaying && !playback.isBuffering) while (isActive) {
            withFrameNanos { position = sample.first + ((SystemClock.elapsedRealtime() - sample.second).coerceIn(0, 650) * playback.speed).toLong() }
        }
    }
    // Canvas has no input handlers: taps and swipe gestures still reach the video controls.
    Canvas(modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }) {
        val time = position - display.offsetMs
        val baseline = topInset - paint.fontMetrics.ascent
        drawIntoCanvas { canvas ->
            timeline.activeAt(time).forEach { entry ->
                paint.color = if (display.showColor) entry.comment.color or 0xFF000000.toInt() else android.graphics.Color.WHITE
                paint.alpha = (display.opacity * 255).toInt()
                canvas.nativeCanvas.drawText(entry.comment.text, entry.xAt(time, size.width), baseline + entry.lane * rowHeight, paint)
            }
        }
    }
}
