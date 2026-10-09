package com.embydog.app.feature.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun EpisodePickerDialog(seriesId: String, initialSeason: String?, accessToken: String, currentItemId: String?, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, viewModel: EpisodePickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(seriesId, initialSeason) { viewModel.loadSeries(seriesId, initialSeason) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选集") }, text = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = episodeColumnCount((maxWidth.value - 24f).coerceAtLeast(1f), LocalDensity.current.fontScale)
            Column {
                SeasonDropdown(state, viewModel::selectSeason)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    episodeGridItems(state.episodes, columns, accessToken, currentItemId, onSelect = {
                        if (it == currentItemId) onDismiss() else onSelect(it)
                    })
                    episodeLoadingItems(state, viewModel::retry, viewModel::loadMore)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
