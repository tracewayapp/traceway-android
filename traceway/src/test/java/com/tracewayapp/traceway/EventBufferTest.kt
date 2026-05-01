package com.tracewayapp.traceway

import com.tracewayapp.traceway.events.EventBuffer
import com.tracewayapp.traceway.events.LogEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class EventBufferTest {

    @Test
    fun keepsEventsInInsertionOrder() {
        val buf = EventBuffer<LogEvent>(windowMs = 60_000, maxSize = 100)
        buf.add(LogEvent("first"))
        buf.add(LogEvent("second"))
        buf.add(LogEvent("third"))

        val snapshot = buf.snapshot()
        assertEquals(listOf("first", "second", "third"), snapshot.map { it.message })
    }

    @Test
    fun dropsOldestWhenMaxSizeExceeded() {
        val buf = EventBuffer<LogEvent>(windowMs = 60_000, maxSize = 3)
        repeat(5) { buf.add(LogEvent("msg-$it")) }

        val snapshot = buf.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("msg-2", "msg-3", "msg-4"), snapshot.map { it.message })
    }

    @Test
    fun pruneDropsEntriesOlderThanWindow() {
        val buf = EventBuffer<LogEvent>(windowMs = 100, maxSize = 100)
        val now = System.currentTimeMillis()
        // Two stale entries from the past, one fresh
        buf.add(LogEvent("stale-1", timestampMs = now - 5_000))
        buf.add(LogEvent("stale-2", timestampMs = now - 3_000))
        buf.add(LogEvent("fresh", timestampMs = now))

        val snapshot = buf.snapshot()
        assertEquals(listOf("fresh"), snapshot.map { it.message })
    }

    @Test
    fun clearEmptiesBuffer() {
        val buf = EventBuffer<LogEvent>(windowMs = 60_000, maxSize = 100)
        buf.add(LogEvent("a"))
        buf.add(LogEvent("b"))
        buf.clear()

        assertEquals(0, buf.length)
        assertEquals(emptyList<LogEvent>(), buf.snapshot())
    }
}
