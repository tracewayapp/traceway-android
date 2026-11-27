@file:JvmName("LogcatSink")

package com.tracewayapp.traceway

import android.util.Log

/** Whether this thread is recording a logcat line: a `Log` call made meanwhile is not recorded again. */
private val recording = object : ThreadLocal<Boolean>() {
    override fun initialValue(): Boolean = false
}

/**
 * The recording half of [TracewayLogcat], after the real `Log` call: hands
 * the line to the running client. Never throws into the app's `Log` call.
 */
internal fun record(priority: Int, tag: String?, msg: String?, tr: Throwable?) {
    guarded("logcat") {
        // Inside the guard: the first touch of TracewayClient runs its static initializer.
        val client = TracewayClient.instance ?: return
        if (recording.get() == true) return
        recording.set(true)
        try {
            client.logcatLine(priority, tag, msg, tr)
        } finally {
            recording.set(false)
        }
    }
}

/** The level of a `Log` priority: verbose trace, debug, info, warn, error, assert (`wtf`) fatal. */
internal fun logcatLevel(priority: Int): TracewayLogLevel = when {
    priority <= Log.VERBOSE -> TracewayLogLevel.TRACE
    priority == Log.DEBUG -> TracewayLogLevel.DEBUG
    priority == Log.INFO -> TracewayLogLevel.INFO
    priority == Log.WARN -> TracewayLogLevel.WARN
    priority == Log.ERROR -> TracewayLogLevel.ERROR
    else -> TracewayLogLevel.FATAL
}
