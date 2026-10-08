package com.embydog.app.core.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackBandwidthMeterTest {
    @Test fun bandwidthUsesBytesReadAndActualElapsedTime() {
        var time = 0L
        val meter = PlaybackBandwidthMeter { time }
        meter.record(2048)
        time = 2_000_000_000L
        assertEquals(1024L, meter.bytesPerSecond())
    }
    @Test fun repeatedUiRefreshesDoNotResetTheSamplingWindow() {
        var time = 0L
        val meter = PlaybackBandwidthMeter { time }
        meter.record(4096)
        time = 500_000_000L
        assertEquals(0L, meter.bytesPerSecond())
        time = 1_000_000_000L
        assertEquals(4096L, meter.bytesPerSecond())
        time = 1_100_000_000L
        assertEquals(4096L, meter.bytesPerSecond())
    }
    @Test fun idleReadsAndEndOfStreamDoNotInflateBandwidth() {
        var time = 0L
        val meter = PlaybackBandwidthMeter { time }
        meter.record(1024); meter.record(-1)
        time = 1_000_000_000L
        assertEquals(1024L, meter.bytesPerSecond())
        time = 2_000_000_000L
        assertEquals(0L, meter.bytesPerSecond())
    }
}
