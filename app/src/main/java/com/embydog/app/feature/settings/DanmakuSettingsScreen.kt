package com.embydog.app.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embydog.app.feature.player.DanmakuDisplayControls
import com.embydog.app.feature.player.DanmakuViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuSettingsScreen(onBack: () -> Unit, viewModel: DanmakuViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var appId by remember(state.appId) { mutableStateOf(state.appId) }
    // Do not restore or display the stored secret in editable text or saved-instance state.
    var secret by remember { mutableStateOf("") }
    var savingSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.saving, state.message) { if (savingSubmitted && !state.saving && state.message == "接入配置已保存") { secret = ""; savingSubmitted = false } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("弹弹 Play 弹幕") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("弹幕由弹弹 Play 开放弹幕网络提供。需要在其开发者中心申请独立的 AppId 和 AppSecret；与 Emby 账号、APK 签名密钥无关。",
                style = MaterialTheme.typography.bodySmall)
            Text("开发者中心：dev.dandanplay.com", style = MaterialTheme.typography.bodySmall)
            Text("AppSecret 加密保存到本机。填写后，搜索作品名与弹幕请求会发送给弹弹 Play，不发送 Emby 地址、账号或令牌。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(appId, { appId = it.take(200) }, label = { Text("AppId") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(secret, { secret = it.take(1024) }, label = { Text("AppSecret") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(if (state.configured) "已保存；同一 AppId 下留空可保留原密钥" else "不使用或内置其他应用的凭据") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { savingSubmitted = true; viewModel.saveCredentials(appId, secret) }, enabled = !state.saving) { Text("保存接入配置") }
                TextButton(onClick = { appId = ""; secret = ""; viewModel.saveCredentials("", "") }, enabled = !state.saving && state.configured) { Text("清除配置") }
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("启用弹幕", style = MaterialTheme.typography.titleMedium)
                Switch(state.display.enabled, { viewModel.updateDisplay(state.display.copy(enabled = it)) })
            }
            DanmakuDisplayControls(state.display, viewModel::updateDisplay)
        }
    }
}
