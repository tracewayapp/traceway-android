package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.core.destinationDigest
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.isTransientFailure
import com.tracewayapp.traceway.otel.OtlpEncoding
import com.tracewayapp.traceway.otel.postOtlp
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import java.io.File
import java.io.FileOutputStream

/**
 * OTLP batches that could not be delivered, kept on disk as protobuf and
 * sent again later: after start, when the app comes back, on the next
 * failed-export cycle, and on the next start after a crash. Bounded in
 * count, age and size so an unreachable server cannot fill the device. One
 * outbox per set of destinations, in its own directory, so batches queued
 * for one receiver never go to another.
 *
 * Files are `<destinationKey>-<epochMs>-<n>.<kind>.pb`, written to a
 * `.tmp` file and renamed, so a crash never leaves half a batch behind.
 *
 * Thread-safe: [add] writes on the calling thread (a crashing one
 * included); [drain] sends on the I/O pool.
 */
internal class Outbox(
    val directory: File?,
    /** By kind: `logs`, `traces`. */
    val destinations: Map<String, OtlpDestination>,
    private val http: HttpTransport,
    private val scheduler: TaskScheduler,
) {
    companion object {
        const val MAX_BATCHES = 200
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        const val MAX_BATCH_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

        private const val MARKER = "destination"

        /**
         * Removes the `outbox-*` directories in [stateDir] other than [keep]:
         * queues of receivers no longer configured, which nothing would ever
         * send.
         */
        fun pruneStale(stateDir: File?, keep: String?) {
            val entries = try {
                stateDir?.listFiles()
            } catch (_: Throwable) {
                null
            } ?: return
            for (entry in entries) {
                if (entry.isDirectory && entry.name.startsWith("outbox-") && entry.name != keep) {
                    try {
                        entry.deleteRecursively()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * Identifies where this outbox sends, its credentials included: batches
     * queued for other destinations or another project's token are dropped,
     * never sent here.
     */
    val destinationKey: String by lazy {
        val parts = ArrayList<String>()
        for (kind in destinations.keys.sorted()) {
            parts.add(kind)
            parts.add(destinations.getValue(kind).key)
        }
        destinationDigest(parts)
    }

    private val lock = Any()
    private val fileLock = Any()
    private var sequence = 0L
    private var timer: Cancellable? = null
    private var attempts = 0

    @Volatile
    private var claimed = false

    @Volatile
    private var disposed = false

    private val drainer = SingleFlight(scheduler) { drainNow() }

    /** Whether batches can be kept: there is a directory and it was claimed. */
    val usable: Boolean get() = claimed && !disposed

    /**
     * Takes the directory: removes half-written files and batches queued
     * for other destinations, and marks it as this one's. Blocks on disk.
     */
    fun claim() {
        val dir = directory ?: return
        try {
            synchronized(fileLock) {
                dir.mkdirs()
                val prefix = "$destinationKey-"
                for (file in dir.listFiles() ?: emptyArray()) {
                    if (!file.isFile) continue
                    val name = file.name
                    if (name.endsWith(".pb.tmp")) {
                        // Left half written by a crash before its rename.
                        file.delete()
                    } else if (name.endsWith(".pb") && !name.startsWith(prefix)) {
                        file.delete()
                    }
                }
                File(dir, MARKER).writeText(destinationKey, Charsets.UTF_8)
            }
            claimed = true
        } catch (_: Throwable) {
            // Disk failures fall back to the live exporter.
        }
    }

    /** Writes an issue before it is sent: the app may not live to see the answer. */
    fun persistLog(record: LogRecordData): Boolean =
        try {
            add(OtlpEncoding.KIND_LOGS, OtlpEncoding.logsRequest(listOf(record)))
        } catch (_: Throwable) {
            false
        }

    /**
     * Writes [body], an OTLP request of [kind], durably on the calling thread
     * and schedules a drain. False when it could not be kept.
     */
    fun add(kind: String, body: ByteArray): Boolean {
        val dir = directory
        if (!claimed || disposed || dir == null || body.isEmpty() || body.size > MAX_BATCH_BYTES ||
            !destinations.containsKey(kind)
        ) {
            return false
        }
        try {
            synchronized(fileLock) {
                val n = synchronized(lock) { sequence++ }
                val file = File(dir, "$destinationKey-${scheduler.now()}-$n.$kind.pb")
                val tmp = File(file.path + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(body)
                    try {
                        out.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    return false
                }
            }
        } catch (_: Throwable) {
            return false
        }
        try {
            prune()
        } catch (_: Throwable) {
            // The batch is already durable. Pruning also runs on drain.
        }
        schedule(backoffMs())
        return true
    }

    /** Batches waiting, for tests and health records. */
    val size: Int get() = try {
        files().size
    } catch (_: Throwable) {
        0
    }

    private fun backoffMs(): Long {
        val n = synchronized(lock) { attempts }.coerceIn(0, 6)
        return minOf(300L, 5L * (1L shl n)) * 1000
    }

    private class Queued(val file: File, val timeMs: Long, val seq: Long)

    /** This outbox's batches, oldest first. */
    private fun files(): List<File> {
        val dir = directory ?: return emptyList()
        val prefix = "$destinationKey-"
        val out = ArrayList<Queued>()
        for (file in dir.listFiles() ?: return emptyList()) {
            val name = file.name
            if (!name.startsWith(prefix) || !name.endsWith(".pb") || !file.isFile) continue
            val parts = name.substring(prefix.length).substringBefore('.').split('-')
            out.add(
                Queued(
                    file,
                    parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                    parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                ),
            )
        }
        out.sortWith(compareBy<Queued>({ it.timeMs }, { it.seq }, { it.file.name }))
        return out.map { it.file }
    }

    private fun timeOf(file: File): Long =
        file.name.removePrefix("$destinationKey-").substringBefore('-').toLongOrNull() ?: 0L

    private fun prune() {
        synchronized(fileLock) {
            val now = scheduler.now()
            val keep = ArrayList<File>()
            var total = 0L
            for (file in files()) {
                val t = timeOf(file)
                // A time far ahead is a clock that moved back: it would never expire.
                if (t < now - MAX_AGE_MS || t > now + MAX_AGE_MS) {
                    file.delete()
                } else {
                    keep.add(file)
                    total += file.length()
                }
            }
            var i = 0
            while (i < keep.size && (keep.size - i > MAX_BATCHES || total > MAX_TOTAL_BYTES)) {
                total -= keep[i].length()
                keep[i].delete()
                i++
            }
        }
    }

    /** Drains after [delayMs], unless a drain is scheduled already. */
    fun schedule(delayMs: Long) {
        if (disposed || directory == null) return
        synchronized(lock) {
            if (timer != null) return
            timer = scheduler.schedule(delayMs) {
                synchronized(lock) { timer = null }
                drain()
            }
        }
    }

    /** Sends what is queued, on the I/O pool; one drain at a time. */
    fun drain(): CompletableResultCode {
        if (disposed || !claimed) return CompletableResultCode.ofSuccess()
        return drainer.run()
    }

    /** [drain] and waits up to [timeoutMs] for it; true when it finished. */
    fun drainBlocking(timeoutMs: Long): Boolean = drain().await(timeoutMs)

    private fun drainNow() {
        if (!claimed || disposed) return
        try {
            prune()
            for (file in files()) {
                if (disposed) return
                val body = try {
                    file.readBytes()
                } catch (_: Throwable) {
                    // Pruned or delivered meanwhile.
                    continue
                }
                val kind = if (file.name.endsWith(".traces.pb")) OtlpEncoding.KIND_TRACES else OtlpEncoding.KIND_LOGS
                val destination = destinations[kind]
                val status = if (destination == null) 400 else postOtlp(http, destination, body)
                if (isTransientFailure(status)) {
                    synchronized(lock) { attempts++ }
                    schedule(backoffMs())
                    return
                }
                // Delivered, or rejected for good: either way it is done.
                synchronized(fileLock) { file.delete() }
            }
            synchronized(lock) { attempts = 0 }
        } catch (_: Throwable) {
            if (!disposed) schedule(backoffMs())
        }
    }

    fun dispose() {
        disposed = true
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
    }
}
