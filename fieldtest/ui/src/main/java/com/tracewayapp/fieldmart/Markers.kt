package com.tracewayapp.fieldmart

import android.util.Log

/**
 * The marker lines of 02-APP-SPEC.md §7, on logcat tag `FM`, and the `FM log <n>` line written once per screen
 * change through the platform logger (check E-12). Every build writes them, so their cost cancels out.
 */
object Markers {
    const val TAG = "FM"
    private const val APP_TAG = "Fieldmart"

    @Volatile
    private var readyAt = 0L

    @Volatile
    private var fullyAt = 0L
    private var screenLogs = 0
    private var screen: String? = null

    val ready: Long get() = readyAt

    fun ready() {
        if (readyAt != 0L) return
        readyAt = System.currentTimeMillis()
        Log.i(TAG, "FM_READY $readyAt")
        Probe.marker("FM_READY", readyAt)
    }

    /** Returns true the first time. */
    fun fully(): Boolean {
        if (fullyAt != 0L) return false
        fullyAt = System.currentTimeMillis()
        Log.i(TAG, "FM_FULLY $fullyAt")
        Probe.marker("FM_FULLY", fullyAt)
        return true
    }

    fun screen(name: String) {
        if (name == screen) return
        screen = name
        val now = System.currentTimeMillis()
        Log.i(TAG, "FM_SCREEN $name $now")
        Probe.marker("FM_SCREEN $name", now)
        screenLogs++
        Log.i(APP_TAG, "FM log $screenLogs")
    }

    fun session(id: String) {
        Log.i(TAG, "FM_SESSION $id")
    }

    fun info(text: String) {
        Log.i(TAG, "FM_INFO $text")
    }
}
