package com.embydog.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.embydog.app.core.media.PlaybackEngineKind
import com.embydog.app.core.media.PlaybackTrackKind
import com.embydog.app.core.media.AUTO_TRACK_ID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PlayerControls(
    state: PlayerUiState,
    visible: Boolean,
    automaticRotation: Boolean,
    onRotate: () -> Unit,
    onAutomaticRotation: () -> Unit,
    onEpisodes: () -> Unit,
    onBusyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviewSeek: (Long) -> Unit,
    onCancelPreview: () -> Unit,
    onEngine: (PlaybackEngineKind) -> Unit,
    onSpeed: (Float) -> Unit,
    onTrack: (PlaybackTrackKind, String?) -> Unit,
    onSleepTimer: (Int, SleepTimerAction) -> Unit = { _, _ -> },
    onCancelSleepTimer: () -> Unit = {},
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var seekPreview by remember(state.engine) { mutableStateOf<Float?>(null) }
    val sliderInteraction = remember { MutableInteractionSource() }
    val sliderDragged by sliderInteraction.collectIsDraggedAsState()
    val playback = state.playback
    LaunchedEffect(dialog, seekPreview) { onBusyChange(dialog != null || seekPreview != null) }
    LaunchedEffect(sliderDragged) {
        if (!sliderDragged && seekPreview != null) { seekPreview = null; onCancelPreview() }
    }
    DisposableEffect(Unit) { onDispose { onBusyChange(false); onCancelPreview() } }
    if (visible || dialog != null) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val compact = compactPlayerControls(maxWidth.value, maxHeight.value)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp).background(Color.Black.copy(alpha = 0.8f)),
            verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(22.dp), tint = Color.White) }
                Text(state.source?.title.orEmpty(), Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.source?.seriesId != null) IconButton(onClick = { onInteraction(); onEpisodes() }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "选集", Modifier.size(22.dp), tint = Color.White)
                }
                IconButton(onClick = { onInteraction(); onAutomaticRotation() }) {
                    Icon(Icons.Default.ScreenRotation, if (automaticRotation) "关闭重力旋转" else "开启重力旋转",
                        Modifier.size(22.dp), tint = if (automaticRotation) Color(0xFF90CAF9) else Color.White)
                }
                IconButton(onClick = { onInteraction(); onRotate() }) {
                    Icon(Icons.Default.StayCurrentLandscape, "手动切换横屏或竖屏", Modifier.size(22.dp), tint = Color.White)
                }
                IconButton(onClick = { onInteraction(); dialog = "timer" }) {
                    Icon(Icons.Default.Timer, "睡眠定时", Modifier.size(22.dp),
                        tint = if (state.sleepTimer != null) Color(0xFF90CAF9) else Color.White)
                }
                IconButton(onClick = { onInteraction(); dialog = "engine" }) {
                    Icon(Icons.Default.Tune, "选择播放内核", Modifier.size(22.dp), tint = Color.White)
                }
        }
        if (state.engine != null && state.errorMessage == null) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.95f))))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.engineKind.label + (playback.decoder?.let { " · $it" } ?: ""),
                        Modifier.weight(1f), color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    Text("↓ ${formatDownloadSpeed(playback.downloadBytesPerSecond)}", color = Color.White,
                        style = MaterialTheme.typography.labelLarge)
                }
                state.sleepTimer?.let { Text("◷ ${formatSleepTimerTime(it.remainingMs)}", color = Color(0xFF90CAF9),
                    style = MaterialTheme.typography.labelSmall) }
                state.notice?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                playback.subtitleMessage?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                Slider(
                    modifier = Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "播放进度" },
                    value = (seekPreview ?: playback.positionMs.toFloat())
                        .coerceIn(0f, playback.durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { seekPreview = it; onPreviewSeek(it.toLong()); onInteraction() },
                    onValueChangeFinished = {
                        seekPreview?.let { onSeek(it.toLong()) }
                        seekPreview = null
                        onCancelPreview()
                        onInteraction()
                    },
                    valueRange = 0f..playback.durationMs.coerceAtLeast(1).toFloat(),
                    enabled = playback.durationMs > 0,
                    interactionSource = sliderInteraction,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF90CAF9),
                        inactiveTrackColor = Color.White.copy(alpha = 0.35f)),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(seekPreview?.toLong() ?: playback.positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(formatPlaybackTime(playback.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().height(52.dp)) {
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onInteraction(); onSeek(playback.positionMs - 10_000) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Replay10, "后退 10 秒", Modifier.size(24.dp), tint = Color.White)
                    }
                    FloatingActionButton(onClick = { onInteraction(); onToggle() }, modifier = Modifier.size(52.dp)
                        .semantics { contentDescription = if (playback.isPlaying) "暂停" else "播放" },
                        containerColor = Color(0xFF90CAF9), contentColor = Color(0xFF071523)) {
                        Icon(if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, Modifier.size(28.dp))
                    }
                    IconButton(onClick = { onInteraction(); onSeek(playback.positionMs + 10_000) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Forward10, "前进 10 秒", Modifier.size(24.dp), tint = Color.White)
                    }
                }
                if (compact) Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TrackControls(playback.speed, onSubtitle = { onInteraction(); dialog = "subtitle" },
                        onAudio = { onInteraction(); dialog = "audio" }, onSpeed = { onInteraction(); dialog = "speed" })
                }
                }
                if (!compact) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                    TrackControls(playback.speed, onSubtitle = { onInteraction(); dialog = "subtitle" },
                        onAudio = { onInteraction(); dialog = "audio" }, onSpeed = { onInteraction(); dialog = "speed" })
                }
            }
        }
        }
    }
    if (dialog == "timer") SleepTimerDialog(state.sleepTimer, onSleepTimer, onCancelSleepTimer,
        onDismiss = { dialog = null; onInteraction() })
    if (dialog != null && dialog != "timer") {
        val title = when (dialog) {
            "engine" -> "播放内核"
            "subtitle" -> "字幕"
            "audio" -> "音轨"
            else -> "播放速度"
        }
        AlertDialog(
            onDismissRequest = { dialog = null; onInteraction() },
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when (dialog) {
                        "engine" -> {
                            Text("标准模式保留磁盘播放缓存；mpv 支持复杂 ASS 和软件解码，当前使用内存缓冲。")
                            PlaybackEngineKind.entries.forEach { kind ->
                                TextButton(onClick = { dialog = null; onInteraction(); onEngine(kind) }) {
                                    Text((if (kind == state.engineKind) "✓ " else "") + kind.label)
                                }
                            }
                            Text("软件解码可能增加耗电，4K 视频是否流畅取决于设备性能。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        "speed" -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            TextButton(onClick = { dialog = null; onInteraction(); onSpeed(speed) }) {
                                Text((if (speed == playback.speed) "✓ " else "") + "$speed×")
                            }
                        }
                        else -> {
                            val kind = if (dialog == "subtitle") PlaybackTrackKind.SUBTITLE else PlaybackTrackKind.AUDIO
                            val tracks = playback.tracks.filter { it.kind == kind }
                            Text(if (kind == PlaybackTrackKind.SUBTITLE) "自动：优先简体中文，其次其他中文，再选默认／强制轨道。" else "自动：优先服务器默认音轨。",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { dialog = null; onInteraction(); onTrack(kind, AUTO_TRACK_ID) }) {
                                Text((if (kind in playback.automaticTracks) "✓ " else "") + "自动选择")
                            }
                            if (kind == PlaybackTrackKind.SUBTITLE) {
                                TextButton(onClick = { dialog = null; onInteraction(); onTrack(kind, null) }) {
                                    Text((if (kind !in playback.automaticTracks && tracks.none { it.selected }) "✓ " else "") + "关闭字幕")
                                }
                            }
                            tracks.forEach { track ->
                                TextButton(modifier = Modifier.fillMaxWidth(),
                                    onClick = { dialog = null; onInteraction(); onTrack(kind, track.id) }) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text((if (track.selected) "✓ " else "") + track.label)
                                        if (track.description.isNotBlank()) Text(track.description,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (tracks.isEmpty()) Text("尚无可选${title}，请等待媒体加载完成")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = null; onInteraction() }) { Text("关闭") }
            },
        )
    }
}

internal fun compactPlayerControls(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 520f || (heightDp < 400f && widthDp >= 480f)

@Composable
private fun TrackControls(speed: Float, onSubtitle: () -> Unit, onAudio: () -> Unit, onSpeed: () -> Unit) {
    val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF303C4E), contentColor = Color.White)
    FilledTonalIconButton(onClick = onSubtitle, modifier = Modifier.size(48.dp), colors = colors) {
        Icon(Icons.Default.Subtitles, "字幕", Modifier.size(22.dp))
    }
    FilledTonalIconButton(onClick = onAudio, modifier = Modifier.size(48.dp), colors = colors) {
        Icon(Icons.Default.Audiotrack, "音轨", Modifier.size(22.dp))
    }
    FilledTonalIconButton(onClick = onSpeed, modifier = Modifier.size(48.dp).semantics { contentDescription = "播放速度" }, colors = colors) {
        Text("${speed}×", style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val total = positionMs.coerceAtLeast(0) / 1000
    val seconds = (total % 60).toString().padStart(2, '0')
    val minutes = (total / 60 % 60).toString().padStart(2, '0')
    return if (total >= 3600) "${total / 3600}:$minutes:$seconds" else "$minutes:$seconds"
}
