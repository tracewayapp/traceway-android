package com.tracewayapp.traceway.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Iso8601 {
    private val formatter: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f
        }
    }

    private val parser: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f
        }
    }

    fun format(epochMs: Long): String = formatter.get()!!.format(Date(epochMs))

    fun parse(value: String): Long {
        return try {
            parser.get()!!.parse(value)?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}
