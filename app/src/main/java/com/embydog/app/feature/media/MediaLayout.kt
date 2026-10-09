package com.embydog.app.feature.media

import kotlin.math.floor

/** Based on the available container width, not device type/orientation. */
internal fun episodeColumnCount(widthDp: Float, fontScale: Float = 1f): Int =
    floor((widthDp + 8f) / (136f * fontScale.coerceIn(1f, 1.5f) + 8f)).toInt().coerceIn(1, 5)

internal fun posterMinimumWidth(fontScale: Float): Float = 100f * fontScale.coerceIn(1f, 1.5f)

/** About two and a half posters on phones, with a comfortable upper bound on tablets. */
internal fun homePosterWidth(widthDp: Float, fontScale: Float = 1f): Float {
    val availableWidth = (widthDp - 24f).coerceAtLeast(1f)
    val baseWidth = ((availableWidth + 10f) / 2.65f - 10f).coerceIn(104f, 152f)
    return (baseWidth * fontScale.coerceIn(1f, 1.35f)).coerceAtMost(availableWidth)
}

internal fun mediaCoverAspectRatio(type: String): Float =
    if (type in setOf("Episode", "CollectionFolder", "Folder")) 16f / 9f else 2f / 3f
