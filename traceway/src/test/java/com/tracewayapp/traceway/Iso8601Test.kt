package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iso8601Test {

    @Test
    fun formatProducesUtcWithMillisecondsAndTrailingZ() {
        val ms = 1_700_000_000_000L // 2023-11-14T22:13:20.000Z
        val formatted = Iso8601.format(ms)
        assertEquals("2023-11-14T22:13:20.000Z", formatted)
    }

    @Test
    fun parseRoundTrip() {
        val ms = 1_700_000_000_123L
        val formatted = Iso8601.format(ms)
        assertEquals(ms, Iso8601.parse(formatted))
    }

    @Test
    fun parseInvalidReturnsZero() {
        assertEquals(0L, Iso8601.parse("not-a-date"))
        assertEquals(0L, Iso8601.parse(""))
    }

    @Test
    fun formattingMatchesFlutterShape() {
        // Flutter's DateTime.toUtc().toIso8601String() emits ms and a 'Z'.
        val formatted = Iso8601.format(System.currentTimeMillis())
        assertTrue(
            "expected ...T...Z with ms, got: $formatted",
            formatted.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$"))
        )
    }
}
