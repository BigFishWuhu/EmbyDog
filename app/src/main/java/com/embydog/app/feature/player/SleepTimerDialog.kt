package com.embydog.app.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun SleepTimerDialog(timer: SleepTimerState?, onSet: (Int, SleepTimerAction) -> Unit,
    onCancel: () -> Unit, onDismiss: () -> Unit) {
    var minutesText by rememberSaveable { mutableStateOf((timer?.minutes ?: 30).toString()) }
    var action by rememberSaveable { mutableStateOf(timer?.action ?: SleepTimerAction.PAUSE) }
    val minutes = sleepTimerMinutes(minutesText)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("睡眠定时") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            timer?.let { Text("${it.action.label} · 剩余 ${formatSleepTimerTime(it.remainingMs)}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall) }
            Text("按实际经过时间计时；暂停或切换播放内核不会重置倒计时。退出当前播放页后取消。",
                style = MaterialTheme.typography.bodySmall)
            SleepTimerAction.entries.forEach { option ->
                OutlinedButton(onClick = { action = option }, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = action == option, onClick = null)
                    Text(option.label, Modifier.padding(start = 8.dp))
                }
            }
            listOf(listOf(15, 30, 60), listOf(90, 120, 180)).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { preset -> FilterChip(selected = minutes == preset, onClick = { minutesText = preset.toString() },
                        label = { Text("$preset 分钟") }, modifier = Modifier.weight(1f)) }
                }
            }
            OutlinedTextField(value = minutesText, onValueChange = { value ->
                if (value.length <= 4 && value.all(Char::isDigit)) minutesText = value
            }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("自定义分钟数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = minutes == null,
                supportingText = { Text("可设置 1–1440 分钟；重新设置会从现在开始计时。") })
            if (timer != null) TextButton(onClick = { onCancel(); onDismiss() }, Modifier.fillMaxWidth()) { Text("取消当前定时") }
        }
    }, confirmButton = { TextButton(enabled = minutes != null, onClick = {
        minutes?.let { onSet(it, action); onDismiss() }
    }) { Text(if (timer == null) "开始计时" else "重新计时") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}

internal fun formatSleepTimerTime(remainingMs: Long): String = formatPlaybackTime((remainingMs.coerceAtLeast(0) + 999L) / 1000L * 1000L)
