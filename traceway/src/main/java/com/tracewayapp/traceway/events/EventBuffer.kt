package com.tracewayapp.traceway.events

import java.util.ArrayDeque
import java.util.Collections

/**
 * Time-windowed FIFO buffer with a hard size cap.
 *
 * Drops entries older than [windowMs] and keeps at most [maxSize] entries.
 * Pruning runs on every [add] and [snapshot] so the buffer is self-maintaining
 * without a background timer.
 */
internal class EventBuffer<T : TracewayEvent>(
    private val windowMs: Long = 10_000L,
    private val maxSize: Int = 200,
) {
    private val queue: ArrayDeque<T> = ArrayDeque()
    private val lock = Any()

    fun add(event: T) {
        synchronized(lock) {
            queue.addLast(event)
            prune()
        }
    }

    /** Returns events ordered oldest -> newest. */
    fun snapshot(): List<T> {
        synchronized(lock) {
            prune()
            return Collections.unmodifiableList(ArrayList(queue))
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }

    val length: Int
        get() = synchronized(lock) { queue.size }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - windowMs
        while (queue.isNotEmpty() && queue.peekFirst().timestampMs < cutoff) {
            queue.removeFirst()
        }
        while (queue.size > maxSize) {
            queue.removeFirst()
        }
    }
}
