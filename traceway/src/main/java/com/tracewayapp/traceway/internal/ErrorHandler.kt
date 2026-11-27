package com.tracewayapp.traceway.internal

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The default uncaught-exception handler: reports the crash through
 * [onCrash] (which writes it, its breadcrumbs and its replay clip to disk
 * and tries a short upload), then hands the throwable to the handler that
 * was installed before, normally the platform's, which ends the process.
 * The crash is never swallowed.
 */
internal class ErrorHandler(
    private val onCrash: (Thread, Throwable) -> Unit,
    /** How long a second thread crashing at the same time waits for the first to finish writing. */
    private val concurrentWaitMs: Long = 3000,
) {
    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** One crash is handled at a time: the first one's writes must finish before the process is ended. */
    private val handling = ReentrantLock()

    /** Set while this thread is inside [onCrash]: a crash in our own handling goes straight to the platform. */
    private val inside = ThreadLocal<Boolean>()

    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> handle(thread, error) }
    }

    private fun handle(thread: Thread, error: Throwable) {
        if (inside.get() != true) {
            var locked = false
            // An interrupted thread could wait for nothing; the flag is put back for the next handler.
            val interrupted = Thread.interrupted()
            try {
                // A second thread crashing meanwhile waits (bounded) rather than ending the process while the first
                // one is still writing; then it is reported too.
                locked = handling.tryLock(concurrentWaitMs, TimeUnit.MILLISECONDS)
                inside.set(true)
                onCrash(thread, error)
            } catch (_: Throwable) {
                // Never swallow the crash because of our own bug (or an interrupt while waiting).
            } finally {
                inside.remove()
                if (locked) handling.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        previous?.uncaughtException(thread, error)
    }
}
