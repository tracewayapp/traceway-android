package com.tracewayapp.traceway.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected epoch values computed with JavaScript's `Date.parse`. */
class Rfc3339Test {

    @Test
    fun `parses UTC timestamps with optional fractions`() {
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26T10:00:00Z"))
        assertEquals(1795687200500L, Rfc3339.parse("2026-11-26T10:00:00.5Z"))
        assertEquals(1795687200123L, Rfc3339.parse("2026-11-26T10:00:00.123456789Z"))
        assertEquals(1795687200120L, Rfc3339.parse("2026-11-26T10:00:00.12z"))
        assertEquals(0L, Rfc3339.parse("1970-01-01T00:00:00Z"))
        assertEquals(1709251199999L, Rfc3339.parse("2024-02-29T23:59:59.999Z"))
        // The form the server writes (Go's time.RFC3339 in UTC).
        assertEquals(1793538000000L, Rfc3339.parse("2026-11-01T13:00:00Z"))
    }

    @Test
    fun `applies numeric offsets`() {
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26T12:30:00+02:30"))
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26T05:00:00-0500"))
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26T10:00:00+00:00"))
    }

    @Test
    fun `accepts a lowercase t or a space, and no offset as UTC`() {
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26t10:00:00Z"))
        assertEquals(1795687200000L, Rfc3339.parse("2026-11-26 10:00:00Z"))
        assertEquals(1795687200000L, Rfc3339.parse(" 2026-11-26T10:00:00 "))
    }

    @Test
    fun `a leap second reads as the second before`() {
        assertEquals(1483228799000L, Rfc3339.parse("2016-12-31T23:59:60Z"))
    }

    @Test
    fun `rejects what is not a timestamp`() {
        for (bad in listOf(
            "",
            "not a date",
            "2026-11-26",
            "2026-11-26T10:00Z",
            "2026-13-01T00:00:00Z",
            "2026-00-01T00:00:00Z",
            "2026-11-32T00:00:00Z",
            "2026-11-26T24:00:00Z",
            "2026-11-26T10:60:00Z",
            "2026-11-26T10:00:61Z",
            "2026-11-26T10:00:00.Z",
            "2026-11-26T10:00:00+5",
            "2026-11-26T10:00:00Zjunk",
        )) {
            assertNull(bad, Rfc3339.parse(bad))
        }
    }

    @Test
    fun `formats UTC with milliseconds`() {
        assertEquals("2026-11-26T10:00:00.123Z", Rfc3339.format(1795687200123L))
        assertEquals("1970-01-01T00:00:00.000Z", Rfc3339.format(0))
        assertEquals("1969-12-31T23:59:59.999Z", Rfc3339.format(-1))
        assertEquals("9999-12-31T23:59:59.999Z", Rfc3339.format(253402300799999L))
        for (ms in listOf(0L, 1L, 1795687200123L, 1709251199999L)) {
            assertEquals(ms, Rfc3339.parse(Rfc3339.format(ms)))
        }
    }
}
