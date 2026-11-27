package com.tracewayapp.traceway.core

internal class BufferEntry<T>(
    val timeMs: Long,
    val signal: String,
    val bytes: Int,
    val item: T,
)

/**
 * Telemetry held on the device until a trigger or capture asks for it.
 * Bounded by total size and by age; the oldest entries go first.
 *
 * Not thread-safe: the client calls it under its lock.
 */
internal class RingBuffer<T>(
    private var maxBytes: Long,
    private var maxAgeMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var entries = ArrayList<BufferEntry<T>>()
    var totalBytes: Long = 0
        private set
    var evictedCount: Int = 0
        private set

    val length: Int get() = entries.size

    /** The most bytes it holds. */
    val limitBytes: Long get() = maxBytes

    fun configure(maxBytes: Long, maxAgeMs: Long) {
        this.maxBytes = maxBytes
        this.maxAgeMs = maxAgeMs
        trim()
    }

    fun push(entry: BufferEntry<T>) {
        entries.add(entry)
        totalBytes += entry.bytes
        trim()
    }

    /** Removes and returns the entries inside the window whose signal is selected, oldest first. `null` selects every signal. */
    fun take(sinceMs: Long, signals: Set<String>?, untilMs: Long? = null): List<BufferEntry<T>> {
        trim()
        val taken = ArrayList<BufferEntry<T>>()
        val kept = ArrayList<BufferEntry<T>>()
        for (entry in entries) {
            if (entry.timeMs >= sinceMs &&
                (untilMs == null || entry.timeMs <= untilMs) &&
                (signals == null || entry.signal in signals)
            ) {
                taken.add(entry)
                totalBytes -= entry.bytes
            } else {
                kept.add(entry)
            }
        }
        entries = kept
        return taken
    }

    fun snapshot(): List<BufferEntry<T>> {
        trim()
        return ArrayList(entries)
    }

    fun retain(keep: (BufferEntry<T>) -> Boolean) {
        val kept = ArrayList<BufferEntry<T>>()
        var bytes = 0L
        for (entry in entries) {
            if (keep(entry)) {
                kept.add(entry)
                bytes += entry.bytes
            }
        }
        entries = kept
        totalBytes = bytes
    }

    fun clear() {
        entries = ArrayList()
        totalBytes = 0
    }

    private fun trim() {
        val cutoff = now() - maxAgeMs
        var drop = 0
        var bytes = totalBytes
        while (drop < entries.size && (bytes > maxBytes || entries[drop].timeMs < cutoff)) {
            bytes -= entries[drop].bytes
            drop++
        }
        if (drop > 0) {
            entries = ArrayList(entries.subList(drop, entries.size))
            totalBytes = bytes
            evictedCount += drop
        }
    }
}
