package com.embydog.app.feature.media

import com.embydog.app.core.model.MediaItem
import com.embydog.app.core.model.MediaPage
import com.embydog.app.core.model.MediaDetail
import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.model.Session
import com.embydog.app.core.model.SubtitleTrack
import com.embydog.app.core.model.SubtitleMetadata
import com.embydog.app.core.media.PlaybackProgressReporter
import com.embydog.app.core.model.describeSubtitle
import com.embydog.app.core.network.BaseItemDto
import com.embydog.app.core.network.EmbyApi
import com.embydog.app.core.network.EmbyUrl
import com.embydog.app.core.network.MediaSourceDto
import com.embydog.app.core.network.MediaStreamDto
import com.embydog.app.core.network.PlaybackInfoRequestDto
import com.embydog.app.core.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class MediaRepository @Inject constructor(
    private val api: EmbyApi,
    private val sessionStore: SessionStore,
    private val progressReporter: PlaybackProgressReporter,
) {
    /** Preparation is metadata-only: do not create a PlaybackInfo session or open live streams. */
    suspend fun getOfflinePlan(itemId: String, selection: PlaybackSelection, owner: Session? = null): com.embydog.app.core.offline.OfflinePlan {
        val session = owner ?: requireNotNull(sessionStore.session.first()) { "请先登录服务器" }
        val baseUrl = EmbyUrl.normalize(session.serverUrl)
        val item = api.getItem(apiUrl(baseUrl, "Users/${session.userId}/Items/$itemId", "Fields" to "$ITEM_FIELDS,MediaSources"), session.accessToken)
        check(item.type in setOf("Movie", "Episode", "Video", "Audio")) { "仅支持缓存原始影片或单集，不支持直播" }
        val source = requireNotNull(item.mediaSources.firstOrNull()) { "服务器没有返回原始媒体文件" }
        // The documented /Items/{Id}/Download API selects the primary file, not an arbitrary version.
        check(selection.mediaSourceId == null || selection.mediaSourceId == source.id) { "此接口仅支持缓存默认媒体版本" }
        val profile = com.embydog.app.core.session.profileId(session.serverUrl, session.userId)
        val record = com.embydog.app.core.offline.OfflineRecord(
            id = com.embydog.app.core.session.privateCacheKey(profile, itemId, source.id), profileId = profile,
            profileName = "${session.serverName} · ${session.userName}", serverUrl = baseUrl.toString(), userId = session.userId,
            itemId = item.id, title = item.name, container = com.embydog.app.core.offline.safeContainer(source.container),
            durationMs = (source.runTimeTicks ?: item.runTimeTicks ?: 0) / TICKS_PER_MILLISECOND,
            selection = selection.copy(mediaSourceId = source.id), tracks = source.mediaStreams.toTrackOptions(),
            seriesTitle = item.seriesName, episodeNumber = item.indexNumber, totalBytes = source.size ?: -1,
            positionMs = (item.userData?.playbackPositionTicks ?: 0) / TICKS_PER_MILLISECOND,
        )
        val external = source.mediaStreams.filter { it.type == "Subtitle" && it.isExternal }
        val text = external.filter { it.codec.lowercase() in setOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa", "ttml", "dfxp") || (it.codec.isBlank() && it.isTextSubtitleStream) }
        return com.embydog.app.core.offline.OfflinePlan(record, apiUrl(baseUrl, "Items/$itemId/Download"),
            text.mapNotNull { it.toExternalSubtitle(item.id, source.id, baseUrl) }, session.accessToken, external.size - text.size)
    }

    suspend fun searchItems(query: String, startIndex: Int = 0, limit: Int = 40): MediaPage = withSession { session, baseUrl ->
        val result = api.getItems(apiUrl(baseUrl, "Users/${session.userId}/Items",
            "SearchTerm" to query.trim(), "Recursive" to true, "IncludeItemTypes" to "Movie,Series,Episode,Video,Audio",
            "StartIndex" to startIndex, "Limit" to limit, "Fields" to ITEM_FIELDS,
            "ImageTypeLimit" to 1, "EnableImageTypes" to "Primary", "EnableTotalRecordCount" to true), session.accessToken)
        MediaPage(result.items.map { it.toMediaItem(baseUrl) }, result.totalRecordCount)
    }

    suspend fun getFavorites(startIndex: Int = 0, limit: Int = 40): MediaPage = withSession { session, baseUrl ->
        val result = api.getItems(apiUrl(baseUrl, "Users/${session.userId}/Items", "Recursive" to true,
            "Filters" to "IsFavorite", "IncludeItemTypes" to "Movie,Series,Episode,Video,Audio",
            "SortBy" to "SortName", "SortOrder" to "Ascending", "StartIndex" to startIndex, "Limit" to limit,
            "Fields" to ITEM_FIELDS, "ImageTypeLimit" to 1, "EnableImageTypes" to "Primary", "EnableTotalRecordCount" to true), session.accessToken)
        MediaPage(result.items.map { it.toMediaItem(baseUrl) }, result.totalRecordCount)
    }

    suspend fun setFavorite(itemId: String, favorite: Boolean) = withSession { session, baseUrl ->
        val url = apiUrl(baseUrl, "Users/${session.userId}/FavoriteItems/$itemId")
        if (favorite) api.addFavorite(url, session.accessToken) else api.removeFavorite(url, session.accessToken)
    }

    suspend fun getSeasons(seriesId: String): List<MediaItem> = withSession { session, baseUrl ->
        api.getItems(apiUrl(baseUrl, "Shows/$seriesId/Seasons", "UserId" to session.userId,
            "Fields" to ITEM_FIELDS, "EnableTotalRecordCount" to true), session.accessToken).items.map { it.toMediaItem(baseUrl) }
    }

    suspend fun getEpisodes(seriesId: String, seasonId: String?, startIndex: Int = 0, limit: Int = 60): MediaPage =
        withSession { session, baseUrl ->
            val query = mutableListOf<Pair<String, Any>>("UserId" to session.userId, "Fields" to ITEM_FIELDS,
                "StartIndex" to startIndex, "Limit" to limit, "EnableTotalRecordCount" to true)
            seasonId?.let { query.add("SeasonId" to it) }
            val result = api.getItems(apiUrl(baseUrl, "Shows/$seriesId/Episodes", *query.toTypedArray()), session.accessToken)
            MediaPage(result.items.map { it.toMediaItem(baseUrl) }, result.totalRecordCount)
        }

    suspend fun getSeriesPlaybackTarget(seriesId: String): MediaItem? = withSession { session, baseUrl ->
        progressReporter.recentEpisode(baseUrl.toString(), session.userId, seriesId)?.let { episodeId ->
            return@withSession getItem(episodeId).withRecentProgress(session)
        }
        val resume = optionalPlaybackItems(apiUrl(baseUrl, "Users/${session.userId}/Items/Resume",
            "ParentId" to seriesId, "Recursive" to true, "IncludeItemTypes" to "Episode", "Limit" to 40,
            "Fields" to ITEM_FIELDS), session.accessToken).firstOrNull { it.seriesId == seriesId }
        if (resume != null) return@withSession resume.toMediaItem(baseUrl).withRecentProgress(session)
        val next = optionalPlaybackItems(apiUrl(baseUrl, "Shows/NextUp", "UserId" to session.userId, "SeriesId" to seriesId,
            "Limit" to 1, "Fields" to ITEM_FIELDS), session.accessToken).firstOrNull { it.seriesId == seriesId }
        if (next != null) return@withSession next.toMediaItem(baseUrl).withRecentProgress(session)
        val seasons = getSeasons(seriesId).sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
        val firstSeason = seasons.firstOrNull { (it.episodeNumber ?: 0) > 0 } ?: seasons.firstOrNull()
        getEpisodes(seriesId, firstSeason?.id, limit = 1).items.firstOrNull()
    }

    private suspend fun optionalPlaybackItems(url: String, token: String): List<BaseItemDto> = try {
        api.getItems(url, token).items
    } catch (error: retrofit2.HttpException) {
        if (error.code() in listOf(400, 404, 405, 501)) emptyList() else throw error
    }

    suspend fun getViews(): List<MediaItem> = withSession { session, baseUrl ->
        api.getItems(
            url = apiUrl(baseUrl, "Users/${session.userId}/Views"),
            accessToken = session.accessToken,
        ).items.map { it.toMediaItem(baseUrl) }
    }

    suspend fun getResumeItems(limit: Int = 12): List<MediaItem> = withSession { session, baseUrl ->
        api.getItems(
            url = apiUrl(
                baseUrl,
                "Users/${session.userId}/Items/Resume",
                "Limit" to limit,
                "MediaTypes" to "Video",
                "Fields" to ITEM_FIELDS,
                "ImageTypeLimit" to 1,
                "EnableImageTypes" to "Primary",
            ),
            accessToken = session.accessToken,
        ).items.map { it.toMediaItem(baseUrl) }
    }

    suspend fun getLatestItems(limit: Int = 20): List<MediaItem> = withSession { session, baseUrl ->
        api.getItemList(
            url = apiUrl(
                baseUrl,
                "Users/${session.userId}/Items/Latest",
                "Limit" to limit,
                "IncludeItemTypes" to "Movie,Series,Episode,Video",
                "Fields" to ITEM_FIELDS,
                "ImageTypeLimit" to 1,
                "EnableImageTypes" to "Primary",
            ),
            accessToken = session.accessToken,
        ).map { it.toMediaItem(baseUrl) }
    }

    suspend fun getLibraryItems(parentId: String): List<MediaItem> = withSession { session, baseUrl ->
        api.getItems(
            url = apiUrl(
                baseUrl,
                "Users/${session.userId}/Items",
                "ParentId" to parentId,
                "Recursive" to true,
                "IncludeItemTypes" to "Movie,Series,Episode,Video",
                "SortBy" to "SortName",
                "SortOrder" to "Ascending",
                "Fields" to ITEM_FIELDS,
                "ImageTypeLimit" to 1,
                "EnableImageTypes" to "Primary",
            ),
            accessToken = session.accessToken,
        ).items.map { it.toMediaItem(baseUrl) }
    }

    suspend fun getItem(itemId: String): MediaItem = withSession { session, baseUrl ->
        api.getItem(
            url = apiUrl(
                baseUrl,
                "Users/${session.userId}/Items/$itemId",
                "Fields" to "$ITEM_FIELDS,MediaSources",
            ),
            accessToken = session.accessToken,
        ).toMediaItem(baseUrl)
    }

    suspend fun getPlaybackSource(
        itemId: String,
        forceTranscode: Boolean = false,
        selection: PlaybackSelection = PlaybackSelection(),
    ): PlaybackSource = withSession { session, baseUrl ->
        val item = api.getItem(
            url = apiUrl(
                baseUrl,
                "Users/${session.userId}/Items/$itemId",
                "Fields" to "$ITEM_FIELDS,MediaSources",
            ),
            accessToken = session.accessToken,
        )
        val playbackInfo = api.getPlaybackInfo(
            url = apiUrl(
                baseUrl,
                "Items/$itemId/PlaybackInfo",
                "UserId" to session.userId,
            ),
            accessToken = session.accessToken,
            request = PlaybackInfoRequestDto(
                userId = session.userId,
                mediaSourceId = selection.mediaSourceId,
                audioStreamIndex = selection.audioIndex,
                subtitleStreamIndex = selection.subtitleIndex,
                startTimeTicks = (progressReporter.recentPosition(baseUrl.toString(), session.userId, item.id)
                    ?: ((item.userData?.playbackPositionTicks ?: 0) / TICKS_PER_MILLISECOND)) * TICKS_PER_MILLISECOND,
                enableDirectPlay = !forceTranscode,
                enableDirectStream = !forceTranscode,
            ),
        )
        val source = playbackInfo.mediaSources.let { allSources ->
            val sources = if (selection.mediaSourceId != null) {
                allSources.filter { it.id == selection.mediaSourceId }
            } else allSources
            if (forceTranscode) {
                sources.firstOrNull { !it.transcodingUrl.isNullOrBlank() }
            } else {
                sources.sortedByDescending { it.supportsDirectPlay }.firstOrNull()
            }
        }
            ?: item.mediaSources.firstOrNull { selection.mediaSourceId == null || it.id == selection.mediaSourceId }
            ?: error(playbackInfo.errorCode ?: "服务器没有返回可播放媒体源")

        if (forceTranscode && source.transcodingUrl.isNullOrBlank()) {
            error("服务器无法为此视频提供兼容转码")
        }

        PlaybackSource(
            itemId = item.id,
            title = item.name,
            uri = source.playbackUri(item.id, baseUrl, forceTranscode),
            accessToken = session.accessToken,
            startPositionMs = progressReporter.recentPosition(baseUrl.toString(), session.userId, item.id)
                ?: ((item.userData?.playbackPositionTicks ?: 0) / TICKS_PER_MILLISECOND),
            subtitles = source.mediaStreams.mapNotNull { stream ->
                stream.toExternalSubtitle(item.id, source.id, baseUrl)
            },
            serverUrl = baseUrl.toString(),
            durationMs = (source.runTimeTicks ?: item.runTimeTicks ?: 0) / TICKS_PER_MILLISECOND,
            tracks = source.mediaStreams.toTrackOptions(),
            selection = selection.copy(mediaSourceId = source.id),
            previewUrl = if (item.mediaSources.size <= 1) apiUrl(baseUrl, "Videos/${item.id}/index.bif", "Width" to 320) else null,
            seriesId = item.seriesId,
            seasonId = item.seasonId,
            seriesTitle = item.seriesName,
            episodeNumber = item.indexNumber,
            userId = session.userId,
            playSessionId = playbackInfo.playSessionId,
            isDirectStream = !forceTranscode && !source.supportsDirectPlay && !source.directStreamUrl.isNullOrBlank(),
            isTranscoded = forceTranscode ||
                (!source.supportsDirectPlay && source.directStreamUrl.isNullOrBlank() &&
                    !source.transcodingUrl.isNullOrBlank()),
        )
    }

    suspend fun getDetail(itemId: String): MediaDetail = withSession { session, baseUrl ->
        val item = api.getItem(
            url = apiUrl(baseUrl, "Users/${session.userId}/Items/$itemId", "Fields" to "$ITEM_FIELDS,MediaSources"),
            accessToken = session.accessToken,
        )
        val source = item.mediaSources.sortedByDescending { it.supportsDirectPlay }.firstOrNull()
        MediaDetail(item.toMediaItem(baseUrl).withRecentProgress(session), source?.mediaStreams.orEmpty().toTrackOptions(), source?.id)
    }

    private fun MediaItem.withRecentProgress(session: Session): MediaItem {
        val position = progressReporter.recentPosition(session.serverUrl, session.userId, id) ?: return this
        return copy(playbackPositionMs = position)
    }

    private fun List<MediaStreamDto>.toTrackOptions(): List<MediaTrackOption> = mapNotNull { stream ->
        if (stream.index < 0) return@mapNotNull null
        val kind = when (stream.type) {
            "Audio" -> MediaTrackKind.AUDIO
            "Subtitle" -> MediaTrackKind.SUBTITLE
            else -> return@mapNotNull null
        }
        val metadata = if (kind == MediaTrackKind.SUBTITLE) stream.subtitleMetadata() else null
        val subtitle = metadata?.let(::describeSubtitle)
        MediaTrackOption(stream.index, kind,
            subtitle?.label ?: 
            stream.displayTitle ?: listOfNotNull(stream.language, stream.codec.takeIf { it.isNotBlank() })
                .joinToString(" · ").ifBlank { "${if (kind == MediaTrackKind.AUDIO) "音轨" else "字幕"} ${stream.index + 1}" },
            stream.language, stream.isExternal, subtitle?.details.orEmpty(), stream.isDefault, stream.isForced, metadata)
    }

    private fun MediaStreamDto.subtitleMetadata() = SubtitleMetadata(index, language, title, displayTitle,
        path?.substringAfterLast('/')?.substringAfterLast('\\'), codec, isExternal, isDefault, isForced, isHearingImpaired)

    private suspend fun <T> withSession(block: suspend (Session, HttpUrl) -> T): T {
        val session = requireNotNull(sessionStore.session.first()) { "登录会话已失效" }
        return block(session, session.serverUrl.toHttpUrl())
    }

    private fun apiUrl(baseUrl: HttpUrl, path: String, vararg query: Pair<String, Any>): String {
        val builder = EmbyUrl.endpoint(baseUrl, path).toHttpUrl().newBuilder()
        query.forEach { (name, value) -> builder.addQueryParameter(name, value.toString()) }
        return builder.build().toString()
    }

    private fun BaseItemDto.toMediaItem(baseUrl: HttpUrl): MediaItem {
        val primaryTag = imageTags["Primary"]
        val displaySubtitle = when {
            type == "Episode" && seriesName != null -> buildString {
                append(seriesName)
                if (parentIndexNumber != null && indexNumber != null) {
                    append(" · S${parentIndexNumber} E${indexNumber}")
                }
            }
            productionYear != null -> productionYear.toString()
            else -> null
        }
        return MediaItem(
            id = id,
            name = name,
            type = type,
            overview = overview,
            productionYear = productionYear,
            runtimeMinutes = runTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() },
            imageUrl = primaryTag?.let {
                apiUrl(
                    baseUrl,
                    "Items/$id/Images/Primary",
                    "maxWidth" to 720,
                    "quality" to 88,
                    "tag" to it,
                )
            },
            imageTag = primaryTag,
            progress = userData?.playedPercentage?.div(100.0)?.toFloat()?.coerceIn(0f, 1f),
            subtitle = displaySubtitle,
            playbackPositionMs = (userData?.playbackPositionTicks ?: 0) / TICKS_PER_MILLISECOND,
            isFavorite = userData?.isFavorite == true,
            seriesId = seriesId,
            seasonId = seasonId,
            episodeNumber = indexNumber,
            seasonNumber = parentIndexNumber,
        )
    }

    private fun MediaSourceDto.playbackUri(
        itemId: String,
        baseUrl: HttpUrl,
        forceTranscode: Boolean,
    ): String {
        val serverUrl = when {
            forceTranscode && !transcodingUrl.isNullOrBlank() -> {
                EmbyUrl.serverPath(baseUrl, transcodingUrl).toString()
            }
            supportsDirectPlay -> apiUrl(
                baseUrl,
                "Videos/$itemId/stream",
                "Static" to true,
                "MediaSourceId" to id,
            )
            !directStreamUrl.isNullOrBlank() -> EmbyUrl.serverPath(baseUrl, directStreamUrl).toString()
            !transcodingUrl.isNullOrBlank() -> EmbyUrl.serverPath(baseUrl, transcodingUrl).toString()
            else -> apiUrl(
                baseUrl,
                "Videos/$itemId/stream",
                "MediaSourceId" to id,
            )
        }
        return serverUrl
    }

    private fun MediaStreamDto.toExternalSubtitle(
        itemId: String,
        mediaSourceId: String,
        baseUrl: HttpUrl,
    ): SubtitleTrack? {
        if (type != "Subtitle" || !(isExternal || isTextSubtitleStream || supportsExternalStream)) {
            return null
        }
        val subtitleUrl = deliveryUrl?.let { EmbyUrl.serverPath(baseUrl, it).toString() }
            ?: apiUrl(
                baseUrl,
                "Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.${codec.ifBlank { "srt" }}",
            )
        return SubtitleTrack(
            uri = subtitleUrl,
            mimeType = codec.toSubtitleMimeType(),
            language = language,
            label = describeSubtitle(subtitleMetadata()).label,
            isDefault = isDefault,
            isForced = isForced,
            streamIndex = index,
            description = describeSubtitle(subtitleMetadata()).details,
        )
    }

    private fun String.toSubtitleMimeType(): String = when (lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "vtt", "webvtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "ttml", "dfxp" -> "application/ttml+xml"
        else -> "application/x-subrip"
    }

    private companion object {
        const val ITEM_FIELDS = "Overview,PrimaryImageAspectRatio,UserData,RunTimeTicks"
        const val TICKS_PER_MILLISECOND = 10_000L
        const val TICKS_PER_MINUTE = 600_000_000L
    }
}
