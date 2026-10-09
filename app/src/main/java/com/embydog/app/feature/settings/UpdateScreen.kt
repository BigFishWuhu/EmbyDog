package com.embydog.app.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.embydog.app.BuildConfig
import com.embydog.app.core.update.UPDATE_REPOSITORY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(state: UpdateUiState, onCheck: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit,
    onInstallError: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var permissionHint by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("检查更新") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("EmbyDog ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
            Text("更新来源：GitHub · $UPDATE_REPOSITORY", style = MaterialTheme.typography.bodySmall)
            Text("检查最新正式版，按设备架构下载。安装前校验 SHA256、包名、版本和签名。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCheck, enabled = !state.checking && !state.downloading, modifier = Modifier.fillMaxWidth()) { Text("检查更新") }
            if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.candidate?.let { candidate ->
                var expandedNotes by remember(candidate.version) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新版本 ${candidate.version}", style = MaterialTheme.typography.titleMedium)
                    Text("${candidate.apk.name} · ${candidate.apk.size / (1024 * 1024)} MiB", style = MaterialTheme.typography.bodySmall)
                    if (candidate.notes.isNotBlank()) {
                        Text(candidate.notes, style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expandedNotes) Int.MAX_VALUE else 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        TextButton(onClick = { expandedNotes = !expandedNotes }) { Text(if (expandedNotes) "收起更新说明" else "展开更新说明") }
                    }
                } }
                if (state.downloading) {
                    LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${state.downloaded / (1024 * 1024)} / ${state.total / (1024 * 1024)} MiB", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancel) { Text("取消下载") }
                } else if (state.file == null) Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("下载更新") }
            }
            if (state.checking) TextButton(onClick = onCancel) { Text("取消检查") }
            state.file?.let { file ->
                if (permissionHint) Text("请允许安装此来源的应用，返回后再次点击安装更新。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    try {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            permissionHint = true
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (_: Exception) { onInstallError() }
                }, modifier = Modifier.fillMaxWidth()) { Text("安装更新") }
            }
        }
    }
}
