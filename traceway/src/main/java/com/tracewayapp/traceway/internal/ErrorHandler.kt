package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.models.ExceptionStackTrace

/**
 * Installs a default uncaught-exception handler that captures the throwable
 * via [TracewayClient], flushes synchronously, then delegates to the previous
 * handler (which is normally the JVM's hard-crash handler — we don't swallow
 * the crash).
 */
internal class ErrorHandler {
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(flushTimeoutMs: Long = 2_000L) {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val client = TracewayClient.instance
                if (client != null) {
                    val formatted = formatException(error)
                    client.addException(
                        ExceptionStackTrace(
                            stackTrace = formatted,
                            recordedAtMs = System.currentTimeMillis(),
                            isMessage = false,
                        )
                    )
                    client.flush(flushTimeoutMs)
                }
            } catch (_: Throwable) {
                // Never swallow the crash because of our own bug.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
