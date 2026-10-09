package com.embydog.app.feature.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.PlaybackSelection

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DetailScreen(accessToken: String, onBack: () -> Unit, onPlay: (String, PlaybackSelection) -> Unit,
    onOpenEpisode: (String) -> Unit, viewModel: DetailViewModel = hiltViewModel(),
    episodeViewModel: EpisodePickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val episodes by episodeViewModel.state.collectAsStateWithLifecycle()
    val currentItem = state.item
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val seriesId = if (currentItem?.type == "Series") currentItem.id else currentItem?.seriesId
    LaunchedEffect(seriesId, currentItem?.seasonId) {
        seriesId?.let { episodeViewModel.loadSeries(it, currentItem?.seasonId) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(state.item?.name ?: "详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                state.item?.let { item -> IconButton(onClick = viewModel::toggleFavorite, enabled = !state.isFavoriteUpdating) {
                    Icon(if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (item.isFavorite) "取消收藏" else "添加收藏",
                        tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                } }
            })
    }) { padding ->
        when {
            state.isLoading -> LoadingContent(Modifier.padding(padding))
            state.errorMessage != null -> ErrorContent(state.errorMessage.orEmpty(), viewModel::retry, Modifier.padding(padding))
            currentItem != null -> BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val columns = episodeColumnCount((maxWidth.value - 24f).coerceAtLeast(1f), LocalDensity.current.fontScale)
                val compactHeader = maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.5f
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                    item {
                        if (currentItem.canPlay || currentItem.type == "Series") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                val target = state.playbackTarget
                                Button(onClick = { target?.let { onPlay(it.id,
                                    if (it.id == currentItem.id) state.selection else PlaybackSelection()) } },
                                    enabled = target != null && !state.isTargetLoading, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Text(if (state.isTargetLoading) "加载播放记录…" else detailPlayLabel(target), Modifier.padding(start = 8.dp))
                                }
                                if (currentItem.type == "Series" && target != null) Text(
                                    listOfNotNull(target.seasonNumber?.let { "第 $it 季" }, target.episodeNumber?.let { "第 $it 集" }, target.name).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.targetError?.let { message -> TextButton(onClick = viewModel::refresh) { Text("$message · 重试") } }
                            }
                        }
                        if (compactHeader) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Poster(currentItem, accessToken, Modifier.width(104.dp).aspectRatio(mediaCoverAspectRatio(currentItem.type)))
                            Column(Modifier.weight(1f)) {
                                Text(currentItem.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(currentItem.productionYear?.toString(), currentItem.runtimeMinutes?.let { "$it 分钟" },
                                    currentItem.type.toDisplayType()).joinToString(" · "), Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Poster(currentItem, accessToken, Modifier.width(96.dp).aspectRatio(mediaCoverAspectRatio(currentItem.type)))
                            Text(currentItem.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            state.actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (currentItem.canPlay) {
                                TrackChoice("音轨", state.tracks.filter { it.kind == MediaTrackKind.AUDIO }, state.selection.audioIndex, false, viewModel::selectAudio)
                                TrackChoice("字幕", state.tracks.filter { it.kind == MediaTrackKind.SUBTITLE }, state.selection.subtitleIndex, true, viewModel::selectSubtitle)
                            }
                        }
                    }
                    if (seriesId != null) {
                        item {
                            Text("剧集", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                            SeasonDropdown(episodes, episodeViewModel::selectSeason, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        }
                        episodeGridItems(episodes.episodes, columns, accessToken, currentItem.id, onOpenEpisode)
                        episodeLoadingItems(episodes, episodeViewModel::retry, episodeViewModel::loadMore)
                    }
                    if (currentItem.overview.isNotBlank()) item {
                        Column(Modifier.padding(12.dp)) {
                            Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(currentItem.overview, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackChoice(title: String, tracks: List<MediaTrackOption>, selected: Int?, allowDisabled: Boolean, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (selected) {
        null -> "自动选择"
        -1 -> "关闭字幕"
        else -> tracks.firstOrNull { it.index == selected }?.label ?: "自动选择"
    }
    val selectedTrack = tracks.firstOrNull { it.index == selected }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("$title：$label", maxLines = 2, overflow = TextOverflow.Ellipsis)
                selectedTrack?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text("自动选择") }, onClick = { expanded = false; onSelect(null) })
            if (allowDisabled) DropdownMenuItem(text = { Text("关闭字幕") }, onClick = { expanded = false; onSelect(-1) })
            tracks.forEach { track ->
                DropdownMenuItem(text = { Column {
                    Text((if (track.index == selected) "✓ " else "") + track.label)
                    if (track.description.isNotBlank()) Text(track.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }, onClick = { expanded = false; onSelect(track.index) })
            }
            if (tracks.isEmpty()) DropdownMenuItem(text = { Text("没有可选$title") }, enabled = false, onClick = {})
        }
    }
}

private fun String.toDisplayType(): String = when (this) {
    "Movie" -> "电影"
    "Episode" -> "剧集"
    "Video" -> "视频"
    "Audio" -> "音频"
    "Series" -> "电视剧"
    else -> this
}
