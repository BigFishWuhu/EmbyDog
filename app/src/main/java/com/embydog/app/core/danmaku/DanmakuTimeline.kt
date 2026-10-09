package com.embydog.app.core.danmaku

data class DanmakuPlacement(val comment: DanmakuComment, val endMs: Long, val lane: Int, val width: Float) {
    val startMs: Long get() = comment.timeMs
    fun xAt(timeMs: Long, viewportWidth: Float): Float = if (comment.mode != DanmakuMode.SCROLL) (viewportWidth - width) / 2
        else viewportWidth - (viewportWidth + width) * (timeMs - startMs).toFloat() / (endMs - startMs)
}

/** Deterministic layout: pause/seek/engine switches do not create stale queues or duplicate comments. */
class DanmakuTimeline(val placements: List<DanmakuPlacement>, val lifetimeMs: Long) {
    fun activeAt(timeMs: Long): List<DanmakuPlacement> {
        var low = 0
        var high = placements.size
        while (low < high) { val mid = (low + high) ushr 1; if (placements[mid].startMs < timeMs - lifetimeMs) low = mid + 1 else high = mid }
        val visible = ArrayList<DanmakuPlacement>()
        var index = low
        while (index < placements.size && placements[index].startMs <= timeMs) {
            val entry = placements[index++]
            if (entry.endMs > timeMs) visible.add(entry)
        }
        return visible
    }

    companion object {
        fun build(comments: List<DanmakuComment>, display: DanmakuDisplay, viewportWidth: Float, laneCount: Int,
            measure: (String) -> Float): DanmakuTimeline {
            val settings = display.normalized()
            val duration = (8000 / settings.speed).toLong()
            if (viewportWidth <= 0 || laneCount <= 0) return DanmakuTimeline(emptyList(), duration)
            val previous = arrayOfNulls<DanmakuPlacement>(laneCount.coerceAtMost(32))
            val placements = ArrayList<DanmakuPlacement>()
            val stride = when (settings.density) { 1 -> 4; 2 -> 2; else -> 1 }
            comments.forEachIndexed { index, comment ->
                if (index % stride != 0 || when (comment.mode) {
                        DanmakuMode.SCROLL -> !settings.showScroll; DanmakuMode.TOP -> !settings.showTop; DanmakuMode.BOTTOM -> !settings.showBottom
                    }) return@forEachIndexed
                val width = measure(comment.text).coerceAtLeast(1f)
                if (!width.isFinite() || width > viewportWidth * 3) return@forEachIndexed
                val lifetime = if (comment.mode == DanmakuMode.SCROLL) duration else (4000 / settings.speed).toLong()
                val candidate = DanmakuPlacement(comment, comment.timeMs + lifetime, 0, width)
                val lanes = if (comment.mode == DanmakuMode.BOTTOM) previous.indices.reversed() else previous.indices.toList()
                val lane = lanes.firstOrNull { lane ->
                    val last = previous[lane]
                    if (last == null || last.endMs <= candidate.startMs) true
                    else if (last.comment.mode != DanmakuMode.SCROLL || comment.mode != DanmakuMode.SCROLL) false
                    else {
                        val gap = 16f
                        val entranceClear = last.xAt(candidate.startMs, viewportWidth) + last.width + gap <= viewportWidth
                        val newSpeed = (viewportWidth + width) / lifetime
                        val exitClear = candidate.startMs + (viewportWidth - gap) / newSpeed >= last.endMs
                        entranceClear && exitClear
                    }
                } ?: return@forEachIndexed
                candidate.copy(lane = lane).let { previous[lane] = it; placements.add(it) }
            }
            return DanmakuTimeline(placements, duration)
        }
    }
}
