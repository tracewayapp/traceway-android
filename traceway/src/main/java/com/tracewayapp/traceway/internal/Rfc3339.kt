package com.tracewayapp.traceway.internal

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * RFC 3339 timestamps without `java.time`, which Android has only from API
 * 26: `2026-11-26T10:00:00Z`, with optional fractional seconds and a
 * numeric offset. Formatting is always UTC with milliseconds.
 */
internal object Rfc3339 {
    private val pattern = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|z|[+-]\d{2}:?\d{2})?$""",
    )
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Epoch milliseconds, or null when [value] is not a timestamp. A missing offset means UTC. */
    fun parse(value: String): Long? {
        val m = pattern.matchEntire(value.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val millis = if (g[7].isEmpty()) 0 else (g[7] + "00").substring(0, 3).toInt()
        val cal = GregorianCalendar(utc)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, minOf(second, 59))
        cal.set(Calendar.MILLISECOND, millis)
        var ms = cal.timeInMillis
        val offset = g[8]
        if (offset.isNotEmpty() && offset != "Z" && offset != "z") {
            val sign = if (offset[0] == '-') -1 else 1
            val digits = offset.substring(1).replace(":", "")
            val oh = digits.substring(0, 2).toInt()
            val om = digits.substring(2, 4).toInt()
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }

    fun format(epochMs: Long): String {
        val cal = GregorianCalendar(utc)
        cal.timeInMillis = epochMs
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND),
        )
    }
}
