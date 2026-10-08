package com.embydog.app.core.media

import java.util.concurrent.atomic.AtomicLong

class PlaybackBandwidthMeter(private val nanoTime: () -> Long = System::nanoTime) {
    private val bytes = AtomicLong()
    private var sampledBytes = 0L
    private var sampledAt = nanoTime()
    private var rate = 0L

    fun record(count: Long) { if (count > 0) bytes.addAndGet(count) }

    @Synchronized fun bytesPerSecond(): Long {
        val now = nanoTime()
        val elapsed = now - sampledAt
        if (elapsed >= 1_000_000_000L) {
            val total = bytes.get()
            rate = ((total - sampledBytes).toDouble() * 1_000_000_000 / elapsed).toLong().coerceAtLeast(0)
            sampledBytes = total
            sampledAt = now
        }
        return rate
    }
}
