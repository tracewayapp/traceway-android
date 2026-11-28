package com.tracewayapp.traceway.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {

    private fun entry(timeMs: Long, signal: String, item: String, bytes: Int = 1) =
        BufferEntry(timeMs = timeMs, signal = signal, bytes = bytes, item = item)

    @Test
    fun `evicts by size and by age, oldest first`() {
        var now = 1000L
        val buffer = RingBuffer<String>(maxBytes = 30, maxAgeMs = 1000, now = { now })
        for (i in 0 until 4) buffer.push(entry(now, "console", "e$i", bytes = 10))
        assertEquals(listOf("e1", "e2", "e3"), buffer.snapshot().map { it.item })
        assertEquals(1, buffer.evictedCount)
        now = 2500
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0L, buffer.totalBytes)
        assertEquals(4, buffer.evictedCount)
    }

    @Test
    fun `take removes only the selected window and signals`() {
        val buffer = RingBuffer<String>(maxBytes = 1000, maxAgeMs = 100_000, now = { 10_000L })
        buffer.push(entry(1000, "console", "old"))
        buffer.push(entry(9000, "console", "log"))
        buffer.push(entry(9500, "network", "net"))
        assertEquals(listOf("log"), buffer.take(8000, setOf("console")).map { it.item })
        assertTrue(buffer.take(8000, emptySet()).isEmpty())
        assertEquals(listOf("old", "net"), buffer.take(0, null).map { it.item })
        assertEquals(0, buffer.length)
        assertEquals(0L, buffer.totalBytes)
    }

    @Test
    fun `take honours the end of the window`() {
        val buffer = RingBuffer<String>(maxBytes = 1000, maxAgeMs = 100_000, now = { 10_000L })
        buffer.push(entry(1000, "console", "a"))
        buffer.push(entry(2000, "console", "b"))
        buffer.push(entry(3000, "console", "c"))
        assertEquals(listOf("a", "b"), buffer.take(0, null, untilMs = 2000).map { it.item })
        assertEquals(listOf("c"), buffer.snapshot().map { it.item })
    }

    @Test
    fun `configure trims to the new bounds, retain and clear keep the byte count`() {
        val buffer = RingBuffer<String>(maxBytes = 100, maxAgeMs = 100_000, now = { 10_000L })
        for (i in 0 until 5) buffer.push(entry(5000L + i, if (i % 2 == 0) "console" else "network", "e$i", bytes = 10))
        assertEquals(50L, buffer.totalBytes)
        buffer.retain { it.signal == "console" }
        assertEquals(listOf("e0", "e2", "e4"), buffer.snapshot().map { it.item })
        assertEquals(30L, buffer.totalBytes)
        buffer.configure(maxBytes = 20, maxAgeMs = 100_000)
        assertEquals(listOf("e2", "e4"), buffer.snapshot().map { it.item })
        assertEquals(20L, buffer.totalBytes)
        buffer.configure(maxBytes = 20, maxAgeMs = 4997)
        assertEquals(listOf("e4"), buffer.snapshot().map { it.item })
        buffer.clear()
        assertEquals(0, buffer.length)
        assertEquals(0L, buffer.totalBytes)
    }
}
