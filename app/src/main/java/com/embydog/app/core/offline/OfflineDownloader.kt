package com.embydog.app.core.offline

import com.embydog.app.core.session.SessionStore
import com.embydog.app.feature.media.MediaRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shared download execution, separate from the foreground-service and notification lifecycle. */
@Singleton
class OfflineDownloader @Inject constructor(
    private val store: OfflineStore,
    private val manager: OfflineManager,
    private val sessions: SessionStore,
    private val repository: MediaRepository,
    private val transport: OfflineTransport,
) {
    suspend fun download(record: OfflineRecord, onProgress: (String, DownloadCheckpoint, Long) -> Unit = { _, _, _ -> }) = withContext(Dispatchers.IO) {
        val id = record.id
        manager.begin(id)
        try {
            guard(id)
            store.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, message = "正在读取媒体信息", bytesPerSecond = 0) }
            val owner = requireNotNull(sessions.sessionForProfile(record.profileId)) { "原 profile 已移除" }
            val plan = repository.getOfflinePlan(record.itemId, record.selection, owner)
            check(plan.record.id == id) { "媒体版本已变化，请重新创建缓存任务" }
            guard(id)
            val directory = store.directory(id)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建缓存目录" }
            val video = store.video(record)
            if (!(video.isFile && video.length() > 0 && video.length() == record.totalBytes)) {
                val partial = store.partial(id)
                val remaining = (plan.record.totalBytes - partial.length()).coerceAtLeast(0)
                check(directory.usableSpace - RESERVE > remaining) { "存储空间不足，请清理空间后继续" }
                var tick = System.nanoTime()
                var lastBytes = partial.length()
                val downloaded = transport.download(id, plan.url, plan.token, partial,
                    DownloadCheckpoint(record.bytes, record.totalBytes, record.validator), guard = { guard(id) }, checkpoint = { progress ->
                        val now = System.nanoTime()
                        val speed = ((progress.bytes - lastBytes).coerceAtLeast(0) * 1_000_000_000L / (now - tick).coerceAtLeast(1))
                        store.update(id) { it.copy(bytes = progress.bytes, totalBytes = progress.total, validator = progress.validator,
                            bytesPerSecond = speed, message = null) }
                        onProgress(record.title, progress, speed)
                        tick = now; lastBytes = progress.bytes
                    })
                guard(id)
                check(partial.renameTo(video)) { "缓存文件保存失败" }
                store.update(id) { it.copy(bytes = downloaded.bytes, totalBytes = downloaded.total, bytesPerSecond = 0, message = "正在缓存外置字幕") }
            }
            val subtitles = mutableListOf<com.embydog.app.core.model.SubtitleTrack>()
            var failedSubtitles = plan.unsupportedExternalSubtitles
            for (subtitle in plan.subtitles) {
                currentCoroutineContext().ensureActive(); guard(id)
                try {
                    val extension = when (subtitle.mimeType) { "text/x-ssa", "text/x-ass" -> "ass"; "text/vtt" -> "vtt"; "application/ttml+xml" -> "ttml"; else -> "srt" }
                    val part = File(directory, "subtitle-${subtitle.streamIndex}.part")
                    val local = File(directory, "subtitle-${subtitle.streamIndex}.$extension")
                    transport.download(id, subtitle.uri, plan.token, part, DownloadCheckpoint(0, -1, null), 16L * 1024 * 1024,
                        authOrigin = record.serverUrl, guard = { guard(id) })
                    check(part.renameTo(local)) { "字幕保存失败" }
                    subtitles += subtitle.copy(uri = local.toURI().toString())
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { guard(id); failedSubtitles++ }
            }
            guard(id)
            store.update(id) { it.copy(status = DownloadStatus.COMPLETED, bytesPerSecond = 0, subtitles = subtitles,
                message = if (failedSubtitles > 0) "$failedSubtitles 条外置字幕未能缓存或格式暂不支持；其余字幕和内嵌音轨可离线使用" else "已缓存原始文件，可断网播放") }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            runCatching { store.update(id) { if (it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED))
                it.copy(status = if (error is DownloadPaused) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                    bytes = store.partial(id).length().takeIf { size -> size > 0 } ?: it.bytes,
                    bytesPerSecond = 0, message = error.offlineMessage()) else it } }
        } finally {
            transport.cancel(id)
            runCatching { store.update(id) { if (it.status == DownloadStatus.PAUSED) it.copy(
                bytes = store.partial(id).length().takeIf { size -> size > 0 } ?: it.bytes, bytesPerSecond = 0) else it } }
            manager.finish(id)
        }
    }

    private fun guard(id: String) {
        if (store.get(id)?.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) throw DownloadPaused("已暂停")
        if (!manager.networkAllowed()) throw DownloadPaused(if (store.state.value.wifiOnly) "Wi-Fi 已断开，点击继续恢复" else "网络已断开，点击继续恢复")
        if (store.directory(id).let { if (it.exists()) it else it.parentFile }.usableSpace < RESERVE) throw DownloadPaused("存储空间不足，清理后点击继续")
    }

    private companion object { const val RESERVE = 64L * 1024 * 1024 }
}
