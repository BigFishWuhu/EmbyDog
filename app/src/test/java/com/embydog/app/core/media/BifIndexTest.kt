package com.embydog.app.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BifIndexTest {
    private fun header(count: Int = 3, multiplier: Int = 1000) = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
        putInt(0); putInt(count); putInt(multiplier)
    }.array()
    private fun table() = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0); putInt(96); putInt(10); putInt(196); putInt(20); putInt(296); putInt(-1); putInt(396)
    }.array()
    @Test fun framePreviewUsesTheNearestEarlierSample() {
        val index = BifParser.parse(header(), table())
        assertEquals(0L, index.frameAt(-1).timeMs)
        assertEquals(10_000L, index.frameAt(15_000).timeMs)
        assertEquals(20_000L, index.frameAt(90_000).timeMs)
        assertEquals(100, index.frameAt(20_000).length)
    }
    @Test fun zeroMultiplierUsesTheBifDefault() { assertEquals(10_000L, BifParser.parse(header(multiplier = 0), table()).frames[1].timeMs) }
    @Test(expected = IllegalArgumentException::class) fun invalidSignatureIsRejected() { BifParser.imageCount(ByteArray(64)) }
    @Test(expected = IllegalArgumentException::class) fun oversizedIndexIsRejected() { BifParser.imageCount(header(count = 30_001)) }
    @Test(expected = IllegalArgumentException::class) fun truncatedIndexIsRejected() { BifParser.parse(header(), table().copyOf(24)) }
    @Test(expected = IllegalArgumentException::class) fun nonIncreasingOffsetsAreRejected() {
        val bytes = table(); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 90)
        BifParser.parse(header(), bytes)
    }
}
