package com.embydog.app.core.offline

import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.core.model.SubtitleTrack
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, COMPLETED }

/** Persist only metadata; credentials and authenticated remote URLs never enter the manifest. */
@Serializable
data class OfflineRecord(
    val id: String,
    val profileId: String,
    val profileName: String,
    val serverUrl: String,
    val userId: String,
    val itemId: String,
    val title: String,
    val container: String,
    val durationMs: Long,
    val selection: PlaybackSelection,
    val tracks: List<MediaTrackOption>,
    val seriesTitle: String? = null,
    val episodeNumber: Int? = null,
    val status: DownloadStatus = DownloadStatus.PAUSED,
    val bytes: Long = 0,
    val totalBytes: Long = -1,
    val validator: String? = null,
    val positionMs: Long = 0,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val message: String? = null,
    val bytesPerSecond: Long = 0,
)

@Serializable
data class OfflineManifest(val records: List<OfflineRecord> = emptyList(), val wifiOnly: Boolean = true)

// Ephemeral download plan. In particular it is NOT serializable.
data class OfflinePlan(val record: OfflineRecord, val url: String, val subtitles: List<SubtitleTrack>, val token: String,
    val unsupportedExternalSubtitles: Int = 0)

internal val offlineIdPattern = Regex("[a-f0-9]{64}")

internal fun safeContainer(value: String): String = value.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "mkv"
