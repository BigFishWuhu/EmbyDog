package com.embydog.app.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.embydog.app.core.model.PlaybackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Singleton
@OptIn(markerClass = [UnstableApi::class])
class MediaPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: androidx.media3.datasource.cache.SimpleCache,
    private val okHttpClient: OkHttpClient,
) {
    fun create(source: PlaybackSource, meter: PlaybackBandwidthMeter = PlaybackBandwidthMeter()): ExoPlayer {
        val upstream = OkHttpDataSource.Factory(okHttpClient.forPlayback(source, meter))
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheDataSource)
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val subtitles = source.subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.uri))
                .setMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .setId("emby-subtitle:${subtitle.streamIndex}")
                .setSelectionFlags(
                    (if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                        (if (subtitle.isForced) C.SELECTION_FLAG_FORCED else 0),
                )
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(source.itemId)
            .setUri(source.uri)
            .setSubtitleConfigurations(subtitles)
            .build()

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(mediaItem, source.startPositionMs)
            }
    }
}
