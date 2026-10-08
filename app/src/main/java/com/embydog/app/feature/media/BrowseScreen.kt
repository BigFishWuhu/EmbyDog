package com.embydog.app.feature.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embydog.app.core.model.MediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(accessToken: String, onOpenItem: (MediaItem) -> Unit, onSettings: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.refresh()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (state.favorites) "我的收藏" else "搜索") },
            actions = {
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "刷新") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "应用设置") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.favorites) OutlinedTextField(value = state.query, onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), singleLine = true, label = { Text("搜索电影、剧集和单集") },
                leadingIcon = { Icon(Icons.Default.Search, null) })
            when {
                state.isLoading -> LoadingContent(Modifier.weight(1f))
                state.items.isEmpty() && state.errorMessage != null -> ErrorContent(state.errorMessage.orEmpty(), viewModel::retry, Modifier.weight(1f))
                state.items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (state.favorites) "还没有收藏，可在详情页添加" else if (state.query.isBlank()) "输入关键词开始搜索" else "没有找到匹配内容")
                }
                else -> {
                    LazyVerticalGrid(columns = GridCells.Adaptive(posterMinimumWidth(LocalDensity.current.fontScale).dp), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.items, key = { it.id }) { item -> MediaCard(item, accessToken, { onOpenItem(item) }, Modifier.fillMaxWidth()) }
                    }
                    state.errorMessage?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                    if (state.hasMore || state.errorMessage != null) TextButton(
                        onClick = if (state.errorMessage != null) viewModel::retry else viewModel::loadMore,
                        enabled = !state.isLoadingMore, modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoadingMore) CircularProgressIndicator(Modifier.size(20.dp))
                        else Text(if (state.errorMessage != null) "重试" else "加载更多")
                    }
                }
            }
        }
    }
}
