package com.embydog.app.feature.media

import kotlin.math.floor

/** Based on the available container width, not device type/orientation. */
internal fun episodeColumnCount(widthDp: Float, fontScale: Float = 1f): Int =
    floor((widthDp + 8f) / (136f * fontScale.coerceIn(1f, 1.5f) + 8f)).toInt().coerceIn(1, 5)

internal fun posterMinimumWidth(fontScale: Float): Float = 100f * fontScale.coerceIn(1f, 1.5f)

internal fun mediaCoverAspectRatio(type: String): Float =
    if (type in setOf("Episode", "CollectionFolder", "Folder")) 16f / 9f else 2f / 3f
