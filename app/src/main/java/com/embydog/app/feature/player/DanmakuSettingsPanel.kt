package com.embydog.app.feature.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.embydog.app.core.danmaku.DanmakuDisplay
import com.embydog.app.core.danmaku.DanmakuEpisode
import kotlin.math.roundToInt

@Composable
fun DanmakuSettingsPanel(state: DanmakuUiState, onDisplay: (DanmakuDisplay) -> Unit,
    onSearch: (String, String) -> Unit, onSelect: (DanmakuEpisode?) -> Unit, onReload: () -> Unit, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(if (state.selected == null) 1 else 0) }
    val parentDensity = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides parentDensity) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(0.94f).height(minOf(540.dp, maxHeight * 0.92f)),
                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("弹幕设置", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(state.display.enabled, { onDisplay(state.display.copy(enabled = it)) })
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭弹幕设置") }
                    }
                    TabRow(selectedTabIndex = tab) {
                        listOf("显示", "匹配弹幕").forEachIndexed { index, label ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                        }
                    }
                    if (tab == 0) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        DanmakuDisplayControls(state.display, onDisplay)
                    } else DanmakuMatchControls(state, onSearch, onSelect, onReload, Modifier.weight(1f))
                }
            }
        }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DanmakuDisplayControls(display: DanmakuDisplay, onChange: (DanmakuDisplay) -> Unit) {
    DanmakuSlider("不透明度", display.opacity, 0.1f..1f, { "${(it * 100).roundToInt()}%" }) { onChange(display.copy(opacity = it)) }
    DanmakuSlider("字体大小", display.textSizeSp, 12f..32f, { "${it.roundToInt()} sp" }) { onChange(display.copy(textSizeSp = it.roundToInt().toFloat())) }
    DanmakuSlider("弹幕速度", display.speed, 0.5f..2f, { "%.1f×".format(it) }) { onChange(display.copy(speed = (it * 10).roundToInt() / 10f)) }
    Text("显示区域", style = MaterialTheme.typography.labelLarge)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.25f to "1/4", 0.5f to "半屏", 0.75f to "3/4", 1f to "全屏").forEach { (area, label) ->
            FilterChip(selected = display.area == area, onClick = { onChange(display.copy(area = area)) }, label = { Text(label) })
        }
    }
    Text("弹幕密度", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1 to "稀疏", 2 to "适中", 3 to "密集").forEach { (value, label) ->
            FilterChip(selected = display.density == value, onClick = { onChange(display.copy(density = value)) }, label = { Text(label) })
        }
    }
    Text("类型屏蔽（关闭即屏蔽）", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
    DanmakuToggle("滚动弹幕", display.showScroll) { onChange(display.copy(showScroll = it)) }
    DanmakuToggle("顶部弹幕", display.showTop) { onChange(display.copy(showTop = it)) }
    DanmakuToggle("底部弹幕", display.showBottom) { onChange(display.copy(showBottom = it)) }
    DanmakuToggle("显示彩色", display.showColor) { onChange(display.copy(showColor = it)) }
    DanmakuToggle("避开底部字幕区域", display.protectSubtitles) { onChange(display.copy(protectSubtitles = it)) }
    Text("时间偏移", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onChange(display.copy(offsetMs = display.offsetMs - 1000)) }) { Text("−1 秒") }
        Text("%+.1f 秒".format(display.offsetMs / 1000f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(display.copy(offsetMs = display.offsetMs + 1000)) }) { Text("+1 秒") }
    }
    Text("负数提前，正数延后；暂停、倍速与拖动进度会同步弹幕。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { onChange(DanmakuDisplay(enabled = display.enabled)) }) { Text("恢复显示默认值") }
}

@Composable
private fun DanmakuToggle(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(selected, onChange)
    }
}

@Composable
private fun DanmakuSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onCommit: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(format(preview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = preview, onValueChange = { preview = it }, onValueChangeFinished = { onCommit(preview) }, valueRange = range)
    }
}

@Composable
private fun DanmakuMatchControls(state: DanmakuUiState, onSearch: (String, String) -> Unit,
    onSelect: (DanmakuEpisode?) -> Unit, onReload: () -> Unit, modifier: Modifier) {
    var query by remember(state.query) { mutableStateOf(state.query) }
    var episode by remember(state.episodeQuery) { mutableStateOf(state.episodeQuery) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("弹幕来自弹弹 Play 开放弹幕网络", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.configured) Text("请在应用设置 → 弹弹 Play 弹幕中配置 AppId 和 AppSecret。", Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(query, { query = it.take(120) }, label = { Text("作品名称（请核对季度）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(episode, { episode = it.take(8) }, label = { Text("集数，可留空") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { onSearch(query, episode) }, enabled = state.configured && query.trim().length >= 2 && !state.searching) { Text("搜索") }
            }
            state.selected?.let {
                Text("当前：${it.label}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onReload, enabled = state.configured && !state.loading && state.display.enabled) { Text("刷新弹幕") }
                    TextButton(onClick = { onSelect(null) }) { Text("解除关联") }
                }
            }
            if (state.loading || state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.searched && state.results.isEmpty()) Text("没有匹配结果，请调整作品名或清空集数后重试。", style = MaterialTheme.typography.bodySmall)
            if (state.hasMore) Text("结果较多，请细化作品名或集数。", style = MaterialTheme.typography.bodySmall)
        }
        items(state.results, key = { it.id }) { candidate ->
            OutlinedButton(onClick = { onSelect(candidate) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text((if (state.selected?.id == candidate.id) "✓ " else "") + candidate.animeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(candidate.episodeTitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
