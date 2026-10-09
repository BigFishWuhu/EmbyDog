package com.embydog.app.feature.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.embydog.app.core.model.MediaItem

@Composable
internal fun SeasonDropdown(state: EpisodePickerState, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = state.seasons.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(state.seasons.firstOrNull { it.id == state.seasonId }?.name ?: "全部剧集", Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ExpandMore, "选择季")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            state.seasons.forEach { season ->
                DropdownMenuItem(text = { Text((if (season.id == state.seasonId) "✓ " else "") + season.name) },
                    onClick = { expanded = false; onSelect(season.id) })
            }
        }
    }
}

internal fun LazyListScope.episodeGridItems(episodes: List<MediaItem>, columns: Int, accessToken: String,
    currentItemId: String?, onSelect: (String) -> Unit, onDownload: ((String) -> Unit)? = null) {
    items(episodes.chunked(columns), key = { row -> "episodes:${row.first().id}" }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { episode -> EpisodeCard(episode, accessToken, episode.id == currentItemId,
                { onSelect(episode.id) }, Modifier.weight(1f), onDownload?.let { { it(episode.id) } }) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun EpisodeCard(episode: MediaItem, accessToken: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, onDownload: (() -> Unit)? = null) {
    Card(modifier.clickable(enabled = episode.canPlay, onClick = onClick), colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Poster(episode, accessToken, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(listOfNotNull(episode.episodeNumber?.let { "第 $it 集" }, if (selected) "当前集" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(episode.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            episode.runtimeMinutes?.let { Text("$it 分钟", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            onDownload?.let { action -> TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.Default.Download, "缓存此集", Modifier.size(18.dp)); Text("缓存", style = MaterialTheme.typography.labelSmall)
            } }
        }
    }
}

internal fun LazyListScope.episodeLoadingItems(state: EpisodePickerState, onRetry: () -> Unit, onMore: () -> Unit) {
    if (state.isLoading) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
    if (!state.isLoading && state.episodes.isEmpty() && state.errorMessage == null) item {
        Text("这一季暂无可播放单集", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
    }
    item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.hasMore || state.errorMessage != null) TextButton(onClick = if (state.errorMessage != null) onRetry else onMore,
                enabled = !state.isLoadingMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isLoadingMore) "加载中…" else if (state.errorMessage != null) "重试" else "加载更多")
            }
        }
    }
}
