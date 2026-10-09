package com.embydog.app.core.danmaku

import org.junit.Assert.*
import org.junit.Test

class DanmakuTimelineTest {
    private val full = DanmakuDisplay(density = 3)
    private fun comment(time: Long, text: String = "普通", mode: DanmakuMode = DanmakuMode.SCROLL) = DanmakuComment(time, mode, 0xFFFFFF, text)

    @Test fun seekingReconstructsOnlyCurrentlyVisibleComments() {
        val timeline = DanmakuTimeline.build(listOf(comment(0), comment(10000)), full, 500f, 1) { 100f }
        assertEquals(1, timeline.activeAt(2000).size)
        assertEquals(0, timeline.activeAt(8000).size)
        assertEquals(1, timeline.activeAt(11000).size)
        assertEquals(0L, timeline.activeAt(1000).single().startMs)
        assertEquals(timeline.activeAt(1000), timeline.activeAt(1000))
        assertTrue(timeline.activeAt(-1).isEmpty())
    }
    @Test fun overcrowdedCommentsDoNotOverlapAtEntrance() {
        val timeline = DanmakuTimeline.build(listOf(comment(0), comment(500), comment(1600)), full, 500f, 1) { 100f }
        assertEquals(listOf(0L, 1600L), timeline.placements.map { it.startMs })
    }
    @Test fun fasterWideCommentCannotCatchPrecedingComment() {
        val timeline = DanmakuTimeline.build(listOf(comment(0, "short"), comment(4000, "wide"), comment(4500, "wide")), full, 500f, 1) {
            if (it == "short") 300f else 500f
        }
        assertEquals(listOf(0L, 4500L), timeline.placements.map { it.startMs })
    }
    @Test fun fixedCommentsHaveSeparateAvailableLanesAndExpire() {
        val timeline = DanmakuTimeline.build(listOf(comment(0, mode = DanmakuMode.TOP), comment(0, mode = DanmakuMode.BOTTOM)), full, 500f, 3) { 100f }
        assertEquals(listOf(0, 2), timeline.placements.map { it.lane })
        assertEquals(200f, timeline.placements.first().xAt(2000, 500f), 0.01f)
        assertTrue(timeline.activeAt(4000).isEmpty())
    }
    @Test fun blockedModesAndDensityAreRespected() {
        val comments = (0L..15L).map { comment(it * 10000) }
        assertEquals(4, DanmakuTimeline.build(comments, full.copy(density = 1), 500f, 1) { 50f }.placements.size)
        assertEquals(8, DanmakuTimeline.build(comments, full.copy(density = 2), 500f, 1) { 50f }.placements.size)
        assertTrue(DanmakuTimeline.build(comments, full.copy(showScroll = false), 500f, 1) { 50f }.placements.isEmpty())
    }
    @Test fun speedChangesLifetimeAndEmptyViewportIsSafe() {
        assertEquals(4000, DanmakuTimeline.build(listOf(comment(0)), full.copy(speed = 2f), 500f, 1) { 100f }.lifetimeMs)
        assertTrue(DanmakuTimeline.build(listOf(comment(0)), full, 0f, 1) { 100f }.placements.isEmpty())
        assertTrue(DanmakuTimeline.build(listOf(comment(0)), full, 500f, 0) { 100f }.placements.isEmpty())
    }
}
