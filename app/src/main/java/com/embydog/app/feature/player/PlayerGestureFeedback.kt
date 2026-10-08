package com.embydog.app.feature.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

internal enum class GestureFeedbackKind { BRIGHTNESS, VOLUME }
internal data class GestureFeedback(val kind: GestureFeedbackKind, val level: Float, val message: String? = null)

@Composable
internal fun PlayerGestureFeedback(preview: SeekPreviewUiState?, feedback: GestureFeedback?, durationMs: Long, modifier: Modifier) {
    if (preview == null && feedback == null) return
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
    val short = maxHeight < 360.dp
    val panelWidth = (maxWidth - 24.dp).coerceIn(160.dp, 240.dp)
    Surface(Modifier.align(Alignment.Center).width(panelWidth).heightIn(max = maxHeight), color = Color.Black.copy(alpha = 0.65f), contentColor = Color.White,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (preview != null) {
                preview.bitmap?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "目标位置附近的画面", Modifier.fillMaxWidth().height(if (short) 64.dp else 108.dp),
                        contentScale = ContentScale.Fit)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val delta = preview.positionMs - preview.originMs
                    Icon(if (delta >= 0) Icons.Default.FastForward else Icons.Default.FastRewind, "定位预览",
                        Modifier.size(28.dp), tint = Color.White.copy(alpha = 0.75f))
                    Column {
                        Text(formatPlaybackTime(preview.positionMs), style = MaterialTheme.typography.titleMedium)
                        Text((if (delta >= 0) "+" else "−") + formatPlaybackTime(kotlin.math.abs(delta)) +
                            " / " + formatPlaybackTime(durationMs), style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.isLoading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White)
                }
                preview.frameTimeMs?.let { Text("画面采样 ${formatPlaybackTime(it)}", style = MaterialTheme.typography.labelSmall) }
                preview.message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text("松手跳转", style = MaterialTheme.typography.labelMedium)
            } else if (feedback != null) {
                val brightness = feedback.kind == GestureFeedbackKind.BRIGHTNESS
                val icon = if (brightness) Icons.Default.BrightnessMedium
                    else if (feedback.level <= 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
                Icon(icon, if (brightness) "亮度" else "音量", Modifier.size(32.dp), tint = Color.White.copy(alpha = 0.75f))
                Text(feedback.message ?: "${if (brightness) "亮度" else "音量"} ${(feedback.level * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { feedback.level.coerceIn(0f, 1f) }, modifier = Modifier.width(160.dp),
                    color = Color.White.copy(alpha = 0.85f), trackColor = Color.White.copy(alpha = 0.2f))
            }
        }
    }
    }
}
