package com.embydog.app.core.media

import android.util.Log
import com.embydog.app.BuildConfig
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.network.EmbyApi
import com.embydog.app.core.network.EmbyUrl
import com.embydog.app.feature.login.DeviceIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@Singleton
class PlaybackProgressReporter @Inject constructor(private val api: EmbyApi, device: DeviceIdentity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authorization = "MediaBrowser Client=\"EmbyDog\", Device=\"Android\", DeviceId=\"${device.id}\", Version=\"${BuildConfig.VERSION_NAME}\""
    private val dispatcher = PlaybackReportDispatcher(scope) { source, event, snapshot ->
        val attempts = if (event == PlaybackReportEvent.STOP) 3 else 1
        for (attempt in 0 until attempts) {
            try {
                withTimeout(8000) { api.reportPlayback(EmbyUrl.endpoint(EmbyUrl.normalize(source.serverUrl), event.path),
                    source.accessToken, authorization, playbackReport(source, snapshot)) }
                break
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (attempt == attempts - 1) Log.w("PlaybackReporting", "Playback sync timed out")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (attempt == attempts - 1) Log.w("PlaybackReporting", "Playback sync failed: ${error.javaClass.simpleName}")
            }
            if (attempt < attempts - 1) delay((attempt + 1) * 1000L)
        }
    }
    internal fun open(source: PlaybackSource, snapshot: PlaybackSnapshot) = dispatcher.open(source, snapshot)
    fun recentPosition(serverUrl: String, userId: String, itemId: String) = dispatcher.recentPosition(serverUrl, userId, itemId)
    fun recentEpisode(serverUrl: String, userId: String, seriesId: String) = dispatcher.recentEpisode(serverUrl, userId, seriesId)
}
