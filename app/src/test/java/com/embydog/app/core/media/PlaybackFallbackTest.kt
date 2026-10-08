package com.embydog.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFallbackTest {
    @Test
    fun `decoder fallback tries mpv then software then one transcode`() {
        val policy = PlaybackFallbackPolicy()
        policy.record(PlaybackEngineKind.MEDIA3)
        assertEquals(PlaybackFallback.SwitchEngine(PlaybackEngineKind.MPV),
            policy.next(PlaybackFailureKind.DECODER, false))
        policy.record(PlaybackEngineKind.MPV)
        assertEquals(PlaybackFallback.SwitchEngine(PlaybackEngineKind.MPV_SOFTWARE),
            policy.next(PlaybackFailureKind.DECODER, false))
        policy.record(PlaybackEngineKind.MPV_SOFTWARE)
        assertEquals(PlaybackFallback.Transcode, policy.next(PlaybackFailureKind.DECODER, false))
        assertEquals(PlaybackFallback.Stop, policy.next(PlaybackFailureKind.DECODER, false))
    }

    @Test
    fun `network failure does not trigger decoder switching or transcoding`() {
        val policy = PlaybackFallbackPolicy()
        policy.record(PlaybackEngineKind.MEDIA3)
        assertEquals(PlaybackFallback.Stop, policy.next(PlaybackFailureKind.NETWORK, false))
    }

    @Test
    fun `missing native library skips software retry`() {
        val policy = PlaybackFallbackPolicy()
        policy.record(PlaybackEngineKind.MEDIA3)
        policy.record(PlaybackEngineKind.MPV)
        assertEquals(PlaybackFallback.Transcode, policy.next(PlaybackFailureKind.INITIALIZATION, false))
    }

    @Test
    fun `transcoded stream failure never enters another fallback loop`() {
        val policy = PlaybackFallbackPolicy()
        assertEquals(PlaybackFallback.Stop, policy.next(PlaybackFailureKind.DECODER, true))
    }

    @Test
    fun `source failure does not waste a software decoding attempt`() {
        val policy = PlaybackFallbackPolicy()
        policy.record(PlaybackEngineKind.MPV)
        assertEquals(PlaybackFallback.Transcode, policy.next(PlaybackFailureKind.SOURCE, false))
    }

    @Test
    fun `manual retry resets attempted engines and transcode state`() {
        val policy = PlaybackFallbackPolicy()
        PlaybackEngineKind.entries.forEach(policy::record)
        policy.next(PlaybackFailureKind.DECODER, false)
        policy.reset()
        policy.record(PlaybackEngineKind.MEDIA3)
        assertEquals(PlaybackFallback.SwitchEngine(PlaybackEngineKind.MPV),
            policy.next(PlaybackFailureKind.DECODER, false))
    }
}
