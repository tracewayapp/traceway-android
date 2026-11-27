package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.guarded
import java.util.PriorityQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A scheduled task that can be called off. Cancelling twice is fine. */
internal fun interface Cancellable {
    fun cancel()
}

/**
 * Time and threads for the SDK. Control logic runs on [schedule]'s single
 * thread (it takes the client's lock); blocking I/O (HTTP, disk) runs on
 * [io], so a slow network never delays a timer.
 *
 * Tests use [ManualScheduler], which runs nothing until told to.
 */
internal interface TaskScheduler {
    /** Wall clock, epoch milliseconds. */
    fun now(): Long

    /** Runs [task] once on the timer thread after [delayMs]. */
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable

    /** Runs [task] on the I/O pool, as soon as a thread is free. */
    fun io(task: () -> Unit)

    /**
     * Runs [task] on the disk thread: local writes that must not wait behind
     * a request stuck on a slow network (the app may be killed in the
     * background before it ends).
     */
    fun disk(task: () -> Unit) = io(task)

    fun shutdown()
}

/** [base], with its I/O on the disk thread: for modules that only write files. */
internal class DiskScheduler(private val base: TaskScheduler) : TaskScheduler by base {
    override fun io(task: () -> Unit) = base.disk(task)
}

internal class ExecutorScheduler(
    private val clock: () -> Long = System::currentTimeMillis,
) : TaskScheduler {
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(daemon("TracewayTimer"))
    private val ioPool: ExecutorService =
        Executors.newFixedThreadPool(2, daemon("TracewayIO"))
    private val diskThread: ExecutorService =
        Executors.newSingleThreadExecutor(daemon("TracewayDisk"))

    override fun now(): Long = clock()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        return try {
            val future = timer.schedule(
                { runGuarded(task) },
                if (delayMs < 0) 0 else delayMs,
                TimeUnit.MILLISECONDS,
            )
            Cancellable { future.cancel(false) }
        } catch (_: Throwable) {
            Cancellable { }
        }
    }

    override fun io(task: () -> Unit) {
        try {
            ioPool.execute { runGuarded(task) }
        } catch (_: Throwable) {
            // Shut down: the work is dropped, as everything else is.
        }
    }

    override fun disk(task: () -> Unit) {
        try {
            diskThread.execute { runGuarded(task) }
        } catch (_: Throwable) {
        }
    }

    override fun shutdown() {
        timer.shutdown()
        ioPool.shutdown()
        diskThread.shutdown()
    }

    /** A failing task must never take the thread down with it (its uncaught handler would end the app). */
    private fun runGuarded(task: () -> Unit) = guarded("scheduled task", task)

    private fun daemon(name: String): ThreadFactory {
        val n = AtomicInteger()
        return ThreadFactory { r ->
            Thread(r, "$name-${n.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}

/**
 * A deterministic scheduler for tests: the clock moves only through
 * [advance], timers fire in order as it passes them, and I/O tasks wait
 * until [runIo] (or run at once with [ioInline]).
 */
internal class ManualScheduler(
    var nowMs: Long = 1_700_000_000_000L,
    var ioInline: Boolean = true,
) : TaskScheduler {
    private class Timer(val at: Long, val seq: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private var seq = 0L
    private val timers = PriorityQueue<Timer>(11, compareBy<Timer>({ it.at }, { it.seq }))
    private val ioQueue = ArrayDeque<() -> Unit>()

    override fun now(): Long = nowMs

    @Synchronized
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Timer(nowMs + maxOf(0L, delayMs), seq++, task)
        timers.add(t)
        return Cancellable { t.cancelled = true }
    }

    override fun io(task: () -> Unit) {
        if (ioInline) {
            runCatching(task)
        } else {
            synchronized(this) { ioQueue.addLast(task) }
        }
    }

    override fun shutdown() {
        synchronized(this) {
            timers.clear()
            ioQueue.clear()
        }
    }

    /** Moves the clock forward by [ms], running every timer due on the way. */
    fun advance(ms: Long) {
        val target = nowMs + ms
        while (true) {
            val next = synchronized(this) {
                val head = timers.peek()
                if (head != null && head.at <= target) timers.poll() else null
            } ?: break
            if (next.cancelled) continue
            if (next.at > nowMs) nowMs = next.at
            runCatching(next.task)
        }
        nowMs = target
    }

    /** Runs the timers due now. */
    fun runDue() = advance(0)

    /** Runs queued I/O tasks, including ones they queue. */
    fun runIo() {
        while (true) {
            val task = synchronized(this) { ioQueue.removeFirstOrNull() } ?: break
            runCatching(task)
        }
    }

    val pendingTimers: Int
        get() = synchronized(this) { timers.count { !it.cancelled } }
}
