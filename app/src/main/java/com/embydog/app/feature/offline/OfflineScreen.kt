package com.embydog.app.feature.offline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embydog.app.core.offline.DownloadStatus
import com.embydog.app.core.offline.OfflineRecord
import java.util.Locale

@Composable
fun rememberOfflinePermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(onBack: () -> Unit, onPlay: (String) -> Unit, viewModel: OfflineViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val permission = rememberOfflinePermissionRequest()
    OfflineContent(state, error, onBack, onPlay, { permission(); viewModel.resume(it) }, viewModel::pause,
        viewModel::delete, viewModel::wifiOnly, viewModel::select)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineContent(state: OfflineUiState, error: String?, onBack: () -> Unit, onPlay: (String) -> Unit,
    onResume: (String) -> Unit, onPause: (String) -> Unit, onDelete: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit, onSelect: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<OfflineRecord?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("离线缓存") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("无需连接服务器，已完成的视频可直接播放。续播进度保存在本机。", style = MaterialTheme.typography.bodySmall)
                ListItem(headlineContent = { Text("仅 Wi-Fi 下载", style = MaterialTheme.typography.bodyMedium) },
                    trailingContent = { Switch(state.wifiOnly, onWifiOnly) })
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(state.profiles.firstOrNull { it.first == state.selected }?.second ?: if (state.selected == null) "全部本机缓存" else "当前 profile",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("全部本机缓存") }, onClick = { menu = false; onSelect(null) })
                        state.profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.second) }, onClick = { menu = false; onSelect(profile.first) }) }
                    }
                }
                (state.readError ?: error)?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            }
            if (state.records.isEmpty()) item { Text("暂无离线缓存。联网时可在影片详情页或剧集卡片中添加。", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium) }
            items(state.records, key = { it.id }) { record ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(listOfNotNull(record.seriesTitle, record.episodeNumber?.let { "第 $it 集" }, record.title).distinct().joinToString(" · "),
                            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(record.profileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${record.status.label()} · ${bytesLabel(record.bytes)} / ${if (record.totalBytes > 0) bytesLabel(record.totalBytes) else "大小未知"}" +
                            if (record.status == DownloadStatus.DOWNLOADING) " · ${bytesLabel(record.bytesPerSecond)}/s" else "", style = MaterialTheme.typography.bodySmall)
                        if (record.status != DownloadStatus.COMPLETED) {
                            if (record.totalBytes > 0) LinearProgressIndicator(progress = { (record.bytes.toDouble() / record.totalBytes).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else if (record.status == DownloadStatus.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        record.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            when (record.status) {
                                DownloadStatus.COMPLETED -> FilledTonalButton(onClick = { onPlay(record.id) }) { Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp)); Text("播放") }
                                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> TextButton(onClick = { onPause(record.id) }) { Icon(Icons.Default.Pause, null, Modifier.size(20.dp)); Text("暂停") }
                                else -> TextButton(onClick = { onResume(record.id) }) { Icon(Icons.Default.Download, null, Modifier.size(20.dp)); Text("继续") }
                            }
                            IconButton(onClick = { deleting = record }) { Icon(Icons.Default.DeleteOutline, "删除缓存", Modifier.size(22.dp)) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除离线缓存？", style = MaterialTheme.typography.titleMedium) },
        text = { Text("将移除「${record.title}」的本机视频、字幕和续播记录，不影响服务器媒体。", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { deleting = null; onDelete(record.id) }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

private fun DownloadStatus.label() = when (this) { DownloadStatus.QUEUED -> "等待下载"; DownloadStatus.DOWNLOADING -> "缓存中";
    DownloadStatus.PAUSED -> "已暂停"; DownloadStatus.FAILED -> "下载失败"; DownloadStatus.COMPLETED -> "已完成" }
private fun bytesLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1024L * 1024))
    else -> "${bytes.coerceAtLeast(0) / 1024} KB"
}
