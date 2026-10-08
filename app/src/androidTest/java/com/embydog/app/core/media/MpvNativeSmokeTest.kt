package com.embydog.app.core.media

import androidx.test.platform.app.InstrumentationRegistry
import dev.jdtech.mpv.MPVLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvNativeSmokeTest {
    @Test
    fun nativeLibraryInitializesWithBothPlaybackConfigurations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(PlaybackEngineKind.MPV, PlaybackEngineKind.MPV_SOFTWARE).forEach { kind ->
            val player = requireNotNull(MPVLib.create(context))
            try {
                mpvOptions(kind, playWhenReady = false).forEach { (name, value) ->
                    assertEquals("Unsupported option: $name", 0, player.setOptionString(name, value))
                }
                player.init()
                assertTrue(player.getPropertyString("mpv-version").orEmpty().contains("mpv"))
                assertEquals(true, player.getPropertyBoolean("pause"))
                assertEquals(if (kind == PlaybackEngineKind.MPV_SOFTWARE) "no" else "mediacodec-copy",
                    player.getPropertyString("hwdec"))
            } finally {
                player.destroy()
            }
        }
    }

    @Test
    fun independentInstancesCanBeReleasedWithoutInvalidatingEachOther() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = requireNotNull(MPVLib.create(context))
        val second = requireNotNull(MPVLib.create(context))
        var firstReleased = false
        try {
            listOf(first, second).forEach {
                it.setOptionString("vo", "null")
                it.setOptionString("ao", "null")
                it.init()
            }
            first.destroy()
            firstReleased = true
            assertTrue(second.getPropertyString("mpv-version").orEmpty().contains("mpv"))
        } finally {
            if (!firstReleased) first.destroy()
            second.destroy()
        }
    }
}
