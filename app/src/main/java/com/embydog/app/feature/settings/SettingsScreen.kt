package com.embydog.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.embydog.app.core.settings.AppSettings
import com.embydog.app.core.settings.PlaybackOrientation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, error: String?, onOrientation: (PlaybackOrientation) -> Unit,
    onBack: () -> Unit, onLogout: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("应用设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text("播放器进入方向", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                Text("默认进入横屏；播放器中的手动旋转只影响本次播放，退出后恢复原方向。",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(PlaybackOrientation.entries) { orientation ->
                ListItem(modifier = Modifier.clickable { onOrientation(orientation) },
                    headlineContent = { Text(orientation.label) }, supportingContent = { Text(orientation.description) },
                    leadingContent = { RadioButton(selected = settings.playbackOrientation == orientation, onClick = null) })
            }
            item {
                error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("退出登录") }
            }
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出登录？") },
        text = { Text("应用设置会保留，下次登录后仍然生效。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
