package com.embydog.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface EmbyApi {
    @POST
    suspend fun reportPlayback(@Url url: String, @Header("X-Emby-Token") accessToken: String,
        @Header("X-Emby-Authorization") authorization: String, @Body request: PlaybackReportDto)

    @POST
    suspend fun addFavorite(@Url url: String, @Header("X-Emby-Token") accessToken: String)

    @DELETE
    suspend fun removeFavorite(@Url url: String, @Header("X-Emby-Token") accessToken: String)

    @GET
    suspend fun getPublicSystemInfo(@Url url: String): PublicSystemInfoDto

    @POST
    suspend fun authenticateByName(
        @Url url: String,
        @Header("X-Emby-Authorization") authorization: String,
        @Body request: AuthenticationRequestDto,
    ): AuthenticationResponseDto

    @GET
    suspend fun getItems(
        @Url url: String,
        @Header("X-Emby-Token") accessToken: String,
    ): ItemsResponseDto

    @GET
    suspend fun getItemList(
        @Url url: String,
        @Header("X-Emby-Token") accessToken: String,
    ): List<BaseItemDto>

    @GET
    suspend fun getItem(
        @Url url: String,
        @Header("X-Emby-Token") accessToken: String,
    ): BaseItemDto

    @POST
    suspend fun getPlaybackInfo(
        @Url url: String,
        @Header("X-Emby-Token") accessToken: String,
        @Body request: PlaybackInfoRequestDto,
    ): PlaybackInfoResponseDto
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PlaybackReportDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @EncodeDefault @SerialName("IsPaused") val isPaused: Boolean = false,
    @EncodeDefault @SerialName("CanSeek") val canSeek: Boolean = true,
    @EncodeDefault @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("EventName") val eventName: String = "TimeUpdate",
)

@Serializable
data class PublicSystemInfoDto(
    @SerialName("Id") val id: String = "",
    @SerialName("ServerName") val serverName: String = "Emby Server",
    @SerialName("Version") val version: String = "",
)

@Serializable
data class AuthenticationRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class AuthenticationResponseDto(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String = "",
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

@Serializable
data class ItemsResponseDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String = "",
    @SerialName("Overview") val overview: String = "",
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("UserData") val userData: UserItemDataDto? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
)

@Serializable
data class UserItemDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
)

@Serializable
data class MediaSourceDto(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Container") val container: String = "",
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
data class MediaStreamDto(
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = -1,
    @SerialName("Codec") val codec: String = "",
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("SupportsExternalStream") val supportsExternalStream: Boolean = false,
)

@Serializable
data class PlaybackInfoRequestDto(
    @SerialName("UserId") val userId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("StartTimeTicks") val startTimeTicks: Long = 0,
    @SerialName("IsPlayback") val isPlayback: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = true,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int = 120_000_000,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfileDto = DeviceProfileDto(),
)

@Serializable
data class DeviceProfileDto(
    @SerialName("Name") val name: String = "EmbyDog Android",
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int = 120_000_000,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Int = 120_000_000,
    @SerialName("MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Int = 320_000,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfileDto> = listOf(
        DirectPlayProfileDto(
            container = "mp4,m4v,mkv,webm,ts,mpegts,m2ts,avi,mov",
            type = "Video",
            videoCodec = "h264,hevc,vp9,av1,mpeg4,mpeg2video",
            audioCodec = "aac,mp3,opus,vorbis,flac,ac3,eac3",
        ),
        DirectPlayProfileDto(
            container = "mp3,aac,m4a,flac,ogg,opus,wav",
            type = "Audio",
        ),
    ),
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfileDto> = listOf(
        TranscodingProfileDto(),
    ),
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfileDto> = listOf(
        SubtitleProfileDto("srt", "External"),
        SubtitleProfileDto("vtt", "External"),
        SubtitleProfileDto("ass", "External"),
        SubtitleProfileDto("ssa", "External"),
        SubtitleProfileDto("ttml", "External"),
        SubtitleProfileDto("pgssub", "Encode"),
        SubtitleProfileDto("dvdsub", "Encode"),
    ),
)

@Serializable
data class DirectPlayProfileDto(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
)

@Serializable
data class TranscodingProfileDto(
    @SerialName("Container") val container: String = "ts",
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String = "h264",
    @SerialName("AudioCodec") val audioCodec: String = "aac",
    @SerialName("Protocol") val protocol: String = "hls",
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("MaxAudioChannels") val maxAudioChannels: String = "2",
    @SerialName("MinSegments") val minSegments: Int = 1,
    @SerialName("SegmentLength") val segmentLength: Int = 6,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true,
    @SerialName("CopyTimestamps") val copyTimestamps: Boolean = true,
    @SerialName("EnableSubtitlesInManifest") val enableSubtitlesInManifest: Boolean = true,
)

@Serializable
data class SubtitleProfileDto(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

@Serializable
data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)
