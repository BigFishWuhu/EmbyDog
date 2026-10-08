package com.embydog.app.core.media

import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.describeSubtitle

/** Chinese-language UI preference; explicit choices always bypass this policy. */
internal fun automaticStreamIndex(options: List<MediaTrackOption>, kind: PlaybackTrackKind): Int? {
    val mediaKind = if (kind == PlaybackTrackKind.AUDIO) MediaTrackKind.AUDIO else MediaTrackKind.SUBTITLE
    val tracks = options.filter { it.kind == mediaKind }
    if (kind == PlaybackTrackKind.AUDIO) return tracks.firstOrNull { it.isDefault }?.index ?: tracks.firstOrNull()?.index
    val chinese = tracks.mapNotNull { track ->
        track.subtitleMetadata?.let(::describeSubtitle)?.chinesePriority?.let { track to it }
    }
    return chinese.sortedWith(compareBy<Pair<MediaTrackOption, Int>> { it.second }
        .thenByDescending { it.first.isDefault }.thenBy { it.first.index }).firstOrNull()?.first?.index
        ?: tracks.firstOrNull { it.isDefault }?.index ?: tracks.firstOrNull { it.isForced }?.index
}
