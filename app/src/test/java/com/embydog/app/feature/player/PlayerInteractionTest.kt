package com.embydog.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerInteractionTest {
    @Test fun horizontalSeekingIsRelativeBoundedAndIndependentOfVideoLength() {
        assertEquals(660_000L, seekGesturePosition(600_000, 150f, 300, 7_200_000))
        assertEquals(0L, seekGesturePosition(5000, -300f, 300, 7_200_000))
        assertEquals(60_000L, seekGesturePosition(50_000, 300f, 300, 60_000))
    }
    @Test fun speedDisplayUsesConsistentUnits() {
        assertEquals("0 B/s", formatDownloadSpeed(0))
        assertEquals("2 KiB/s", formatDownloadSpeed(2048))
        assertEquals("1.5 MiB/s", formatDownloadSpeed(1572864))
    }
    @Test fun seekingIsClampedToMediaDuration() {
        assertEquals(0L, clampSeekPosition(-10_000, 60_000))
        assertEquals(60_000L, clampSeekPosition(70_000, 60_000))
        assertEquals(30_000L, clampSeekPosition(30_000, 60_000))
    }

    @Test fun seekingBeforeDurationIsKnownDoesNotJumpToZero() {
        assertEquals(30_000L, clampSeekPosition(30_000, 0))
    }

    @Test fun draggingUpIncreasesLevelAndDraggingDownDecreasesIt() {
        assertEquals(0.7f, adjustedGestureLevel(0.5f, -100f, 1000), 0.001f)
        assertEquals(0.3f, adjustedGestureLevel(0.5f, 100f, 1000), 0.001f)
    }

    @Test fun gesturesRespectBrightnessFloorAndVolumeBounds() {
        assertEquals(0.02f, adjustedGestureLevel(0.5f, 1000f, 1000, 0.02f), 0f)
        assertEquals(0f, adjustedGestureLevel(0.5f, 1000f, 1000), 0f)
        assertEquals(1f, adjustedGestureLevel(0.5f, -1000f, 1000), 0f)
    }
}
