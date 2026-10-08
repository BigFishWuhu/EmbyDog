package com.embydog.app.feature.player

import org.junit.Assert.*
import org.junit.Test

class ResponsivePlayerLayoutTest {
    @Test fun narrowPortraitKeepsTwoRowsAndLandscapeUsesCompactControls() {
        assertFalse(compactPlayerControls(320f, 640f))
        assertFalse(compactPlayerControls(411f, 891f))
        assertTrue(compactPlayerControls(640f, 320f))
        assertTrue(compactPlayerControls(480f, 280f))
        assertFalse(compactPlayerControls(440f, 280f))
        assertTrue(compactPlayerControls(800f, 1200f))
        assertFalse(compactPlayerControls(320f, 280f))
    }
}
