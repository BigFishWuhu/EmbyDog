package com.embydog.app.core.model

data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String,
    val productionYear: Int?,
    val runtimeMinutes: Int?,
    val imageUrl: String?,
    val imageTag: String?,
    val progress: Float?,
    val subtitle: String?,
    val playbackPositionMs: Long,
    val isFavorite: Boolean = false,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val episodeNumber: Int? = null,
    val seasonNumber: Int? = null,
) {
    val canPlay: Boolean get() = type in PLAYABLE_TYPES

    private companion object {
        val PLAYABLE_TYPES = setOf("Movie", "Episode", "Video", "Audio")
    }
}

data class MediaSection(
    val title: String,
    val items: List<MediaItem>,
)

data class MediaPage(val items: List<MediaItem>, val totalCount: Int)

data class SubtitleTrack(
    val uri: String,
    val mimeType: String,
    val language: String?,
    val label: String,
    val isDefault: Boolean,
    val isForced: Boolean,
    val streamIndex: Int = -1,
    val description: String = "",
)

enum class MediaTrackKind { AUDIO, SUBTITLE }

data class MediaTrackOption(
    val index: Int,
    val kind: MediaTrackKind,
    val label: String,
    val language: String?,
    val isExternal: Boolean,
    val description: String = "",
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val subtitleMetadata: SubtitleMetadata? = null,
)

// null = server/player default, -1 = explicitly disable subtitles.
data class PlaybackSelection(
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val mediaSourceId: String? = null,
)

data class MediaDetail(
    val item: MediaItem,
    val tracks: List<MediaTrackOption>,
    val mediaSourceId: String?,
)

data class PlaybackSource(
    val itemId: String,
    val title: String,
    val uri: String,
    val accessToken: String,
    val startPositionMs: Long,
    val subtitles: List<SubtitleTrack>,
    val serverUrl: String,
    val isTranscoded: Boolean = false,
    val durationMs: Long = 0,
    val tracks: List<MediaTrackOption> = emptyList(),
    val selection: PlaybackSelection = PlaybackSelection(),
    val previewUrl: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
)
