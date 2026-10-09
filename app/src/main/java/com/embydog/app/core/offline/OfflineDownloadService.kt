package com.embydog.app.core.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import com.embydog.app.MainActivity
import com.embydog.app.core.session.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class OfflineDownloadService : Service() {
    @Inject lateinit var store: OfflineStore
    @Inject lateinit var manager: OfflineManager
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var downloader: OfflineDownloader
    @Inject lateinit var transport: OfflineTransport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue: Job? = null
    @Volatile private var activeId: String? = null
    private var profilesJob: Job? = null
    private var latestStartId = 0
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = pauseForNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                (store.state.value.wifiOnly && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) pauseForNetwork()
        }
    }
    private fun pauseForNetwork() {
        scope.launch {
            store.state.value.records.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }.forEach {
                manager.pause(it.id)
                store.update(it.id) { record -> if (record.status == DownloadStatus.PAUSED) record.copy(message = "网络不符合下载设置，恢复网络后点击继续") else record }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "离线缓存", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("正在准备缓存"))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        profilesJob = scope.launch {
            sessions.state.collectLatest { state ->
                store.state.value.records.filter { record -> record.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) &&
                    state.profiles.none { it.id == record.profileId } }.forEach { manager.pause(it.id) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(this) { latestStartId = startId }
        if (intent?.action == PAUSE) {
            scope.launch {
                store.state.value.records.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING) }.forEach { manager.pause(it.id) }
                stopSelf()
            }
        } else synchronized(this) { if (queue?.isActive != true) launchQueue() }
        return START_NOT_STICKY
    }

    private fun launchQueue() {
        queue = scope.launch {
            try {
                while (true) {
                    val record = store.state.value.records.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                    activeId = record.id
                    download(record)
                    activeId = null
                }
            } finally {
                activeId = null
                synchronized(this@OfflineDownloadService) {
                    queue = null
                    if (store.state.value.records.any { it.status == DownloadStatus.QUEUED } && scope.isActive)
                        launchQueue() else stopSelfResult(latestStartId)
                }
            }
        }
    }

    private suspend fun download(record: OfflineRecord) = downloader.download(record, ::notify)

    private fun notify(title: String, checkpoint: DownloadCheckpoint, speed: Long) {
        val progress = if (checkpoint.total > 0) ((checkpoint.bytes.toDouble() / checkpoint.total) * 100).toInt().coerceIn(0, 100) else null
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(title,
            "${checkpoint.bytes / (1024 * 1024)} MB · ${speed / 1024} KB/s", progress))
    }
    private fun notification(title: String, text: String = "后台缓存中，可暂停后继续", progress: Int? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, OfflineDownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progress ?: 0, progress == null).addAction(android.R.drawable.ic_media_pause, "暂停全部", pause).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        activeId?.let(transport::cancel)
        scope.cancel()
        runBlocking(Dispatchers.IO) {
            store.state.value.records.filter { it.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED) }.forEach {
                runCatching { store.update(it.id) { value -> value.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0, message = "后台下载已停止，点击继续") } }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "offline-downloads"
        private const val NOTIFICATION = 401
        private const val PAUSE = "com.embydog.app.offline.PAUSE"
    }
}
