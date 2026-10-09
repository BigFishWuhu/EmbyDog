package com.embydog.app.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun SleepTimerDialog(timer: SleepTimerState?, onSet: (Int, SleepTimerAction) -> Unit,
    onCancel: () -> Unit, onDismiss: () -> Unit) {
    var minutesText by rememberSaveable { mutableStateOf((timer?.minutes ?: 30).toString()) }
    var action by rememberSaveable { mutableStateOf(timer?.action ?: SleepTimerAction.PAUSE) }
    val minutes = sleepTimerMinutes(minutesText)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(max = (maxHeight - 24.dp).coerceAtLeast(120.dp)),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("睡眠定时", fontSize = 18.sp, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        timer?.let { Text("剩余 ${formatSleepTimerTime(it.remainingMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                        OutlinedTextField(value = minutesText, onValueChange = { value ->
                            if (value.length <= 4 && value.all(Char::isDigit)) minutesText = value
                        }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("多少分钟后停止", fontSize = 12.sp) },
                            suffix = { Text("分钟", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = minutes == null)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SleepTimerAction.entries.forEach { option -> FilterChip(selected = action == option, onClick = { action = option },
                                label = { Text(if (option == SleepTimerAction.PAUSE) "到时暂停" else "到时退出", fontSize = 12.sp) }, modifier = Modifier.weight(1f)) }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(15, 30, 60, 90, 120, 180)) { preset ->
                                FilterChip(selected = minutes == preset, onClick = { minutesText = preset.toString() },
                                    label = { Text("$preset 分钟", fontSize = 11.sp) })
                            }
                        }
                        Text("1–1440 分钟 · 退出当前播放页后取消", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (timer != null) TextButton(onClick = { onCancel(); onDismiss() }, Modifier.fillMaxWidth()) { Text("取消定时", fontSize = 12.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("返回", fontSize = 13.sp) }
                        TextButton(enabled = minutes != null, onClick = { minutes?.let { onSet(it, action); onDismiss() } }) {
                            Text(if (timer == null) "开始计时" else "重新计时", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

internal fun formatSleepTimerTime(remainingMs: Long): String = formatPlaybackTime((remainingMs.coerceAtLeast(0) + 999L) / 1000L * 1000L)
