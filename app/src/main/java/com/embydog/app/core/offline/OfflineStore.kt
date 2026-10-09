package com.embydog.app.core.offline

import android.content.Context
import android.util.AtomicFile
import com.embydog.app.core.model.PlaybackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

@Singleton
class OfflineStore internal constructor(private val root: File) : AutoCloseable {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "offline"))
    private val json = Json { ignoreUnknownKeys = true }
    private val manifest = AtomicFile(File(root, "manifest.json"))
    private val mutable = MutableStateFlow(OfflineManifest())
    val state = mutable.asStateFlow()
    private val writeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val positions = kotlinx.coroutines.channels.Channel<Pair<String, Long>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    var readError: String? = null
        private set

    init {
        writeScope.launch {
            for ((id, position) in positions) runCatching { savePosition(id, position) }
        }
        try {
            root.mkdirs()
            if (manifest.baseFile.exists() || File(root, "manifest.json.bak").exists()) {
                val loaded = manifest.openRead().use { json.decodeFromString<OfflineManifest>(it.readBytes().decodeToString()) }
                require(loaded.records.all { offlineIdPattern.matches(it.id) })
                mutable.value = loaded.copy(records = loaded.records.map { record ->
                    if (record.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED))
                        record.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0, message = "下载已中断，点击继续") else record.copy(bytesPerSecond = 0)
                })
            }
        } catch (_: Exception) { readError = "缓存清单读取失败，已有视频文件未删除；请勿清除应用数据" }
    }

    @Synchronized
    private fun commit(value: OfflineManifest) {
        check(readError == null) { readError.orEmpty() }
        val output = manifest.startWrite()
        try {
            output.write(json.encodeToString(value).toByteArray())
            manifest.finishWrite(output)
        } catch (error: Exception) { manifest.failWrite(output); throw error }
        mutable.value = value
    }

    @Synchronized fun put(record: OfflineRecord) {
        require(offlineIdPattern.matches(record.id))
        commit(mutable.value.copy(records = mutable.value.records.filterNot { it.id == record.id } + record))
    }
    @Synchronized fun update(id: String, transform: (OfflineRecord) -> OfflineRecord) {
        val old = get(id) ?: return
        val changed = transform(old).also { require(it.id == old.id && it.profileId == old.profileId) }
        if (changed != old) put(changed)
    }
    @Synchronized fun get(id: String): OfflineRecord? = mutable.value.records.firstOrNull { it.id == id }
    @Synchronized fun wifiOnly(value: Boolean) = commit(mutable.value.copy(wifiOnly = value))

    fun directory(id: String): File {
        require(offlineIdPattern.matches(id))
        return File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    fun partial(id: String) = File(directory(id), "media.part")
    fun video(record: OfflineRecord) = File(directory(record.id), "media.${safeContainer(record.container)}")

    @Synchronized fun delete(id: String) {
        val record = get(id) ?: return
        check(record.status !in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED)) { "请先暂停下载" }
        val directory = directory(id)
        check(!directory.exists() || directory.deleteRecursively()) { "删除缓存文件失败" }
        commit(mutable.value.copy(records = mutable.value.records.filterNot { it.id == id }))
    }

    @Synchronized fun savePosition(id: String, position: Long) = update(id) {
        it.copy(positionMs = position.coerceIn(0, it.durationMs.takeIf { duration -> duration > 0 } ?: Long.MAX_VALUE))
    }
    fun savePositionAsync(id: String, position: Long) { positions.trySend(id to position) }
    override fun close() { positions.close(); writeScope.cancel() }

    fun playback(id: String): PlaybackSource {
        val record = requireNotNull(get(id)) { "找不到离线缓存" }
        check(record.status == DownloadStatus.COMPLETED) { "缓存尚未完成" }
        val file = video(record)
        check(file.isFile && file.length() > 0 && (record.totalBytes < 0 || file.length() == record.totalBytes)) { "本地视频文件缺失或不完整" }
        val subtitles = record.subtitles.filter { subtitle ->
            runCatching { File(java.net.URI(subtitle.uri)).let { it.canonicalFile.parentFile == directory(id).canonicalFile && it.isFile } }.getOrDefault(false)
        }
        val indices = subtitles.map { it.streamIndex }.toSet()
        return PlaybackSource(record.itemId, record.title, file.toURI().toString(), "", record.positionMs,
            subtitles, record.serverUrl, durationMs = record.durationMs,
            tracks = record.tracks.filter { !it.isExternal || it.index in indices }, selection = record.selection,
            userId = record.userId, seriesTitle = record.seriesTitle, episodeNumber = record.episodeNumber, offlineId = id)
    }
}
