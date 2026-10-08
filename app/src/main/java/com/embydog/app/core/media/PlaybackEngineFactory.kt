package com.embydog.app.core.media

import android.content.Context
import com.embydog.app.core.model.PlaybackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
class PlaybackEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media3Factory: MediaPlayerFactory,
    private val httpClient: OkHttpClient,
) {
    fun create(source: PlaybackSource, kind: PlaybackEngineKind, playWhenReady: Boolean): PlaybackEngine =
        when (kind) {
            PlaybackEngineKind.MEDIA3 -> {
                val meter = PlaybackBandwidthMeter()
                Media3PlaybackEngine(media3Factory.create(source, meter), source, meter)
                    .also { if (!playWhenReady) it.pause() }
            }
            PlaybackEngineKind.MPV, PlaybackEngineKind.MPV_SOFTWARE ->
                MpvPlaybackEngine(context, source, kind, httpClient, playWhenReady)
        }
}
