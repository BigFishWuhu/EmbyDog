package com.embydog.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.embydog.app.core.session.ProfileState
import com.embydog.app.core.session.ProfileSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(state: ProfileState, error: String?, busy: Boolean, onSelect: (String) -> Unit,
    onRemove: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    var removing by remember { mutableStateOf<ProfileSummary?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Profiles") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = { IconButton(onClick = onAdd, enabled = !busy) { Icon(Icons.Default.Add, "添加 profile") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("每个 profile 保存一个服务器账号。启动时自动进入上次使用的 profile。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.profiles, key = { it.id }) { profile ->
                Card { ListItem(modifier = Modifier.clickable(enabled = !busy) { onSelect(profile.id) },
                    headlineContent = { Text("${profile.userName} · ${profile.serverName}") },
                    supportingContent = { Text(profile.serverUrl, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { RadioButton(selected = profile.id == state.activeId, onClick = null, enabled = !busy) },
                    trailingContent = { IconButton(onClick = { removing = profile }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "移除 ${profile.userName} profile") } }) }
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("添加 profile") }
            }
        }
    }
    removing?.let { profile -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 profile？") },
        text = { Text("仅从本机移除 ${profile.userName} 的登录配置，不会删除 Emby 账号或服务器数据。") },
        confirmButton = { TextButton(onClick = { removing = null; onRemove(profile.id) }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
