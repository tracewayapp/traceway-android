package com.tracewayapp.traceway

import android.util.Log

/**
 * Runs [block] and contains any failure in it: the SDK never takes the app
 * down (CONTRACT §13). Use it around everything the SDK runs inside the
 * app's control flow: the public API, hooks it installs (callbacks,
 * listeners, wrappers, interceptors) and its own threads and timers.
 *
 * Nothing is allocated or logged when [block] succeeds: a `try` around an
 * inlined block. A failure is logged once per [where] when debug logging is
 * on ([TracewayOptions.debug]) and is otherwise silent; it is never
 * reported through Traceway itself and never rethrown. An interrupt is put
 * back on the thread.
 *
 * Only for the SDK's own work: an app's exception that must reach the app
 * (the block an interaction runs, the response of an intercepted call, an
 * uncaught crash) is let through by the caller, outside the block.
 */
internal inline fun guarded(where: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        Guard.failed(where, e)
    }
}

/** [guarded] for a block with a result: [fallback] when it fails. */
internal inline fun <T> guarded(where: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (e: Throwable) {
        Guard.failed(where, e)
        fallback
    }

/** The failure side of [guarded], kept out of line. */
internal object Guard {
    private const val TAG = "Traceway"
    private const val MAX_PLACES = 256

    /** Whether failures are logged; set by a client started with [TracewayOptions.debug]. */
    @Volatile
    @JvmField
    var debug = false

    private val logged = HashSet<String>()

    @JvmStatic
    fun failed(where: String, error: Throwable) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        if (!debug) return
        try {
            val first = synchronized(logged) { logged.size < MAX_PLACES && logged.add(where) }
            if (first) Log.d(TAG, "$where failed (contained): $error")
        } catch (_: Throwable) {
            // Not even the log line may escape.
        }
    }
}
