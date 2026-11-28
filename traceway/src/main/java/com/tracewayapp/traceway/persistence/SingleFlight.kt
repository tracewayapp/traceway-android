package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.internal.TaskScheduler
import io.opentelemetry.sdk.common.CompletableResultCode
import java.util.concurrent.TimeUnit

/**
 * Runs [work] on the I/O pool, one run at a time: asking while a run is
 * going returns that run, like a Dart `_running ??= ...` future. The
 * result always succeeds; it only tells when the run is over.
 */
internal class SingleFlight(
    private val scheduler: TaskScheduler,
    private val work: () -> Unit,
) {
    private val lock = Any()
    private var running: CompletableResultCode? = null

    val isRunning: Boolean get() = synchronized(lock) { running != null }

    fun run(): CompletableResultCode {
        val result: CompletableResultCode
        synchronized(lock) {
            running?.let { return it }
            result = CompletableResultCode()
            running = result
        }
        scheduler.io {
            try {
                work()
            } catch (_: Throwable) {
                // A failing run must not wedge the next one.
            } finally {
                synchronized(lock) { running = null }
                result.succeed()
            }
        }
        return result
    }

    /** Waits for the run going on, if any, then runs again: what was added meanwhile is included. */
    fun runFresh(): CompletableResultCode {
        val current = synchronized(lock) { running } ?: return run()
        val result = CompletableResultCode()
        current.whenComplete { run().whenComplete { result.succeed() } }
        return result
    }
}

/** Waits up to [timeoutMs] for [this]; true when it completed in time. */
internal fun CompletableResultCode.await(timeoutMs: Long): Boolean =
    try {
        join(maxOf(0L, timeoutMs), TimeUnit.MILLISECONDS).isDone
    } catch (_: Throwable) {
        isDone
    }
