package com.embydog.app.core.settings

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test fun firstLaunchAndUnknownPreferenceDefaultToLandscape() {
        assertEquals(PlaybackOrientation.LANDSCAPE, AppSettings().playbackOrientation)
        assertEquals(PlaybackOrientation.LANDSCAPE, PlaybackOrientation.fromStored(null))
        assertEquals(PlaybackOrientation.LANDSCAPE, PlaybackOrientation.fromStored("invalid"))
    }
    @Test fun orientationNamesRoundTripWithoutConfusingEntryAndSensorRotation() {
        PlaybackOrientation.entries.forEach { assertEquals(it, PlaybackOrientation.fromStored(it.name)) }
        assertNotEquals(PlaybackOrientation.LANDSCAPE, PlaybackOrientation.SENSOR)
    }
}
