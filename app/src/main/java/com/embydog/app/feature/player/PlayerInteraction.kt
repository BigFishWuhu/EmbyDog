package com.embydog.app.feature.player

internal fun clampSeekPosition(positionMs: Long, durationMs: Long): Long =
    if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

// An upward drag across half of the video height covers the full adjustment range.
internal fun adjustedGestureLevel(initial: Float, distanceY: Float, height: Int, minimum: Float = 0f): Float =
    (initial - distanceY / height.coerceAtLeast(1) * 2f).coerceIn(minimum, 1f)

internal enum class GestureAxis { UNDECIDED, HORIZONTAL, VERTICAL }

internal fun seekGesturePosition(originMs: Long, distanceX: Float, width: Int, durationMs: Long): Long {
    val windowMs = minOf(durationMs.coerceAtLeast(0), 120_000L)
    return clampSeekPosition(originMs + (distanceX / width.coerceAtLeast(1) * windowMs).toLong(), durationMs)
}

internal fun formatDownloadSpeed(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0)
    return when {
        value >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MiB/s", value / (1024.0 * 1024))
        value >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KiB/s", value / 1024.0)
        else -> "$value B/s"
    }
}
