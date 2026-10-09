package com.embydog.app.feature.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.embydog.app.core.model.MediaItem
import com.embydog.app.core.model.Session

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeScreen(
    session: Session,
    onOpenLibrary: (MediaItem) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onSettings: () -> Unit,
    onProfiles: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session.serverName) },
                actions = {
                    IconButton(onClick = onProfiles) { Icon(Icons.Default.AccountCircle, "切换 profile") }
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "应用设置")
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingContent(Modifier.padding(contentPadding))
            state.errorMessage != null &&
                state.views.isEmpty() &&
                state.resumeItems.isEmpty() &&
                state.latestItems.isEmpty() -> ErrorContent(
                message = state.errorMessage.orEmpty(),
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(contentPadding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "你好，${session.userName}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.views.isNotEmpty()) {
                    item {
                        MediaRow(
                            title = "媒体库",
                            items = state.views,
                            accessToken = session.accessToken,
                            onItemClick = onOpenLibrary,
                        )
                    }
                }
                if (state.resumeItems.isNotEmpty()) {
                    item {
                        MediaRow(
                            title = "继续观看",
                            items = state.resumeItems,
                            accessToken = session.accessToken,
                            onItemClick = onOpenItem,
                        )
                    }
                }
                if (state.latestItems.isNotEmpty()) {
                    item {
                        MediaRow(
                            title = "最近添加",
                            items = state.latestItems,
                            accessToken = session.accessToken,
                            onItemClick = { item ->
                                if (item.type == "Series") onOpenLibrary(item) else onOpenItem(item)
                            },
                        )
                    }
                }
                state.errorMessage?.let { message ->
                    item {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    items: List<MediaItem>,
    accessToken: String,
    onItemClick: (MediaItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val posterWidth = ((maxWidth - 24.dp) / 3.25f - 8.dp).coerceIn(88.dp, 120.dp)
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaCard(item, accessToken, onClick = { onItemClick(item) }, modifier = Modifier.width(posterWidth))
            }
        }
    }
    }
}
