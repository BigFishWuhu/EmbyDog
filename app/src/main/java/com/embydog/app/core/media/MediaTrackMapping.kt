package com.embydog.app.core.media

import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption

internal fun mediaTrackForNative(
    options: List<MediaTrackOption>, kind: PlaybackTrackKind, ordinal: Int, ffmpegIndex: Int? = null,
): MediaTrackOption? {
    val mediaKind = if (kind == PlaybackTrackKind.AUDIO) MediaTrackKind.AUDIO else MediaTrackKind.SUBTITLE
    val candidates = options.filter { it.kind == mediaKind && !it.isExternal }
    return ffmpegIndex?.let { index -> candidates.firstOrNull { it.index == index } }
        ?: candidates.getOrNull(ordinal)
}
