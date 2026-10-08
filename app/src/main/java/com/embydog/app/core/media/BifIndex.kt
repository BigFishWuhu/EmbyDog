package com.embydog.app.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class BifFrame(val timeMs: Long, val offset: Long, val length: Int)
internal class BifIndex(val frames: List<BifFrame>) {
    fun frameAt(positionMs: Long): BifFrame {
        val found = frames.binarySearch { it.timeMs.compareTo(positionMs.coerceAtLeast(0)) }
        val index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        return frames[index]
    }
}

internal object BifParser {
    const val HEADER_SIZE = 64
    const val MAX_IMAGES = 30_000
    const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private val signature = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)
    private fun uint(value: Int) = value.toLong() and 0xffffffffL

    fun imageCount(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE && header.copyOfRange(0, 8).contentEquals(signature))
        val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(8) == 0) { "Unsupported BIF version" }
        val count = uint(bytes.getInt(12))
        require(count in 1..MAX_IMAGES.toLong())
        return count.toInt()
    }

    fun parse(header: ByteArray, table: ByteArray): BifIndex {
        val count = imageCount(header)
        require(table.size == (count + 1) * 8)
        val multiplier = uint(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(16)).let {
            if (it == 0L) 1000L else it
        }
        require(multiplier <= 60_000)
        val bytes = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        val frames = (0 until count).map { index ->
            val time = uint(bytes.getInt(index * 8))
            val offset = uint(bytes.getInt(index * 8 + 4))
            val end = uint(bytes.getInt((index + 1) * 8 + 4))
            require(time != 0xffffffffL && offset >= HEADER_SIZE + table.size && end > offset)
            require(end - offset <= MAX_FRAME_BYTES)
            BifFrame(time * multiplier, offset, (end - offset).toInt())
        }
        require(uint(bytes.getInt(count * 8)) == 0xffffffffL)
        require(frames.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs && b.offset > a.offset })
        return BifIndex(frames)
    }
}
