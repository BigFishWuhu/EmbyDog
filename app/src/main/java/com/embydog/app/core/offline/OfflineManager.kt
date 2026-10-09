package com.embydog.app.core.offline

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.core.session.SessionStore
import com.embydog.app.feature.media.MediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class OfflineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: OfflineStore,
    private val repository: MediaRepository,
    private val sessions: SessionStore,
    private val transport: OfflineTransport,
) {
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal fun begin(id: String) { running.add(id) }
    internal fun finish(id: String) { running.remove(id) }
    private suspend fun awaitIdle(id: String) {
        check(withTimeoutOrNull(25_000) { while (id in running) delay(50); true } == true) { "后台下载正在停止，请稍后重试" }
    }
    fun networkAllowed(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!store.state.value.wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    suspend fun enqueue(itemId: String, selection: PlaybackSelection): String = withContext(Dispatchers.IO) {
        val plan = repository.getOfflinePlan(itemId, selection)
        val existing = store.get(plan.record.id)
        if (existing?.status == DownloadStatus.COMPLETED) return@withContext "此视频已缓存，可从离线缓存页播放"
        if (existing?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)) return@withContext "已在缓存队列中"
        store.put(existing ?: plan.record)
        resume(plan.record.id)
        if (networkAllowed()) "已加入离线缓存队列" else "已创建任务，连接 Wi-Fi 后请在离线缓存页继续"
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val record = requireNotNull(store.get(id)) { "缓存任务不存在" }
        if (record.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED)) return@withContext
        awaitIdle(id)
        check(sessions.sessionForProfile(record.profileId) != null) { "原 profile 已移除，请重新添加该账号以继续下载；已完成的视频仍可离线播放" }
        if (!networkAllowed()) {
            store.update(id) { it.copy(status = DownloadStatus.PAUSED, message = if (store.state.value.wifiOnly) "等待 Wi-Fi，连接后点击继续" else "无可用网络，联网后点击继续") }
            return@withContext
        }
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, message = null, bytesPerSecond = 0) }
        try { ContextCompat.startForegroundService(context, Intent(context, OfflineDownloadService::class.java)) }
        catch (error: Exception) { store.update(id) { it.copy(status = DownloadStatus.PAUSED, message = "无法启动后台下载，请返回应用后重试") }; throw error }
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        try {
            store.update(id) { if (it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING))
                it.copy(status = DownloadStatus.PAUSED, bytes = store.partial(id).length().takeIf { size -> size > 0 } ?: it.bytes,
                    bytesPerSecond = 0, message = "已暂停") else it }
        } finally { transport.cancel(id) }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { pause(id); awaitIdle(id); store.delete(id) }
    suspend fun wifiOnly(value: Boolean) = withContext(Dispatchers.IO) {
        store.wifiOnly(value)
        if (!networkAllowed()) store.state.value.records.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }.forEach { pause(it.id) }
    }
}

internal fun Throwable.offlineMessage(): String = when (this) {
    is DownloadPaused -> message ?: "下载已暂停"
    is IllegalStateException, is IllegalArgumentException -> message?.take(180) ?: "缓存失败，请重试"
    is retrofit2.HttpException -> "服务器请求失败（HTTP ${code()}），请检查账号与下载权限"
    else -> "缓存失败，请检查网络、服务器和存储空间后重试"
}
