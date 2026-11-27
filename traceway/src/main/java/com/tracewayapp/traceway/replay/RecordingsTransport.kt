package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.gzip
import com.tracewayapp.traceway.persistence.SingleFlight
import com.tracewayapp.traceway.persistence.await
import io.opentelemetry.sdk.common.CompletableResultCode
import java.io.File
import java.io.FileOutputStream

internal class RecordingSegment(
    /** The `traceway.exception.id` of the exception this segment is a clip of. */
    val exceptionId: String? = null,
    val sessionId: String? = null,
    val segmentIndex: Int? = null,
    /** rrweb events, JSON values. */
    val events: List<Map<String, Any?>>,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    /** Size of the events as JSON when known; see [SessionRecordingPayload.approxBytes]. */
    val approxBytes: Int? = null,
) {
    /** What the segment weighs in the upload queue. */
    val bytes: Long
        get() {
            approxBytes?.let { return it.toLong() }
            var total = 0L
            for (event in events) {
                val data = event["data"]
                if (data is String) total += data.length
            }
            return total
        }

    fun toJson(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (exceptionId != null) out["exceptionId"] = exceptionId
        if (sessionId != null) out["sessionId"] = sessionId
        if (segmentIndex != null) out["segmentIndex"] = segmentIndex
        out["events"] = events
        if (startedAtMs != null) out["startedAt"] = Rfc3339.format(startedAtMs)
        if (endedAtMs != null) out["endedAt"] = Rfc3339.format(endedAtMs)
        return out
    }
}

/**
 * Sends replay clips to the dedicated, non-OpenTelemetry recordings
 * endpoint as gzipped `{"segments": [...]}` JSON. Failed batches are
 * retried a few times with growing delays and then dropped, so a server
 * that keeps rejecting them cannot grow memory. What waits is bounded by
 * [maxQueuedBytes] too: a device that streams while offline drops its
 * oldest segments.
 *
 * A process about to die writes what waits to [spoolDirectory] ([spool]);
 * the next start sends it ([loadSpooled]). Thread-safe; requests run on
 * the I/O pool.
 */
internal class RecordingsTransport(
    val url: String,
    private val token: String,
    private val http: HttpTransport,
    private val scheduler: TaskScheduler,
    retryDelaysMs: List<Long>? = null,
    val maxQueuedBytes: Long = DEFAULT_MAX_QUEUED_BYTES,
    /** Where [spool] keeps batches for the next start; null keeps nothing. */
    private val spoolDirectory: File? = null,
) {
    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(5_000L, 30_000L, 120_000L)
        val PERMANENT_FAILURES: Set<Int> = setOf(400, 401, 403, 404, 413, 422)
        const val DEFAULT_MAX_QUEUED_BYTES = 10L * 1024 * 1024
        const val TIMEOUT_MS = 30_000

        /** Spooled batches older than this are not sent any more. */
        const val SPOOL_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val SPOOL_SUFFIX = ".json.gz"

        fun encode(segments: List<RecordingSegment>): ByteArray =
            gzip(Json.stringify(mapOf("segments" to segments.map { it.toJson() })).toByteArray(Charsets.UTF_8))
    }

    private class Batch(
        var segments: List<RecordingSegment>?,
        /** The gzipped request body, once encoded or as read from the spool. */
        var body: ByteArray?,
        val count: Int,
        val bytes: Long,
        var spoolFile: File? = null,
    ) {
        var attempts = 0
    }

    private val retryDelaysMs: List<Long> = retryDelaysMs ?: DEFAULT_RETRY_DELAYS_MS
    private val lock = Any()
    private val queue = ArrayDeque<Batch>()
    private var queuedBytes = 0L
    private var inFlight: Batch? = null
    private var timer: Cancellable? = null
    private var generation = 0
    private var spoolSequence = 0
    private var spoolLoaded = false
    private var failed = 0
    private var sent = 0
    private val drainer = SingleFlight(scheduler) { drainNow() }

    val failedSegments: Int get() = synchronized(lock) { failed }

    /** Called (without the transport's lock) after segments were given up: evicted, refused or out of retries. */
    @Volatile
    var onFailed: (() -> Unit)? = null

    private fun failedNow() {
        try {
            onFailed?.invoke()
        } catch (_: Throwable) {
        }
    }
    val sentSegments: Int get() = synchronized(lock) { sent }

    /** Segments waiting to be sent. */
    val pending: Int get() = synchronized(lock) { queue.sumOf { it.count } }

    fun send(segments: List<RecordingSegment>) {
        if (segments.isEmpty()) return
        var bytes = 0L
        for (s in segments) bytes += s.bytes
        enqueue(Batch(segments, null, segments.size, bytes))
        kick()
    }

    private fun enqueue(batch: Batch) {
        val evicted = ArrayList<Batch>()
        synchronized(lock) {
            queue.addLast(batch)
            queuedBytes += batch.bytes
            while (queuedBytes > maxQueuedBytes && queue.size > 1) {
                val dropped = queue.removeFirst()
                queuedBytes -= dropped.bytes
                failed += dropped.count
                evicted.add(dropped)
            }
        }
        for (b in evicted) b.spoolFile?.delete()
        if (evicted.isNotEmpty()) failedNow()
    }

    /** Starts a drain unless one waits for a retry delay. */
    private fun kick() {
        synchronized(lock) { if (timer != null) return }
        drainer.run()
    }

    /** Drops everything waiting (replay was switched off), spooled batches included. */
    fun discard() {
        val dropped: List<Batch>
        synchronized(lock) {
            generation++
            dropped = ArrayList(queue)
            queue.clear()
            queuedBytes = 0
            timer?.cancel()
            timer = null
        }
        for (b in dropped) b.spoolFile?.delete()
    }

    /** Sends what waits now, retry delays notwithstanding; completes when the queue was tried. */
    fun flush(): CompletableResultCode {
        synchronized(lock) {
            timer?.cancel()
            timer = null
        }
        return drainer.runFresh()
    }

    /** [flush] and waits up to [timeoutMs]; true when it finished. */
    fun flushBlocking(timeoutMs: Long): Boolean = flush().await(timeoutMs)

    private fun scheduleRetry(delayMs: Long) {
        synchronized(lock) {
            if (timer != null) return
            timer = scheduler.schedule(delayMs) {
                synchronized(lock) { timer = null }
                drainer.run()
            }
        }
    }

    private fun drainNow() {
        while (true) {
            val batch: Batch
            val gen: Int
            synchronized(lock) {
                batch = queue.removeFirstOrNull() ?: return
                queuedBytes -= batch.bytes
                inFlight = batch
                gen = generation
            }
            val retryAfterMs = post(batch)
            var done: File? = null
            synchronized(lock) {
                inFlight = null
                if (gen != generation || retryAfterMs == null) {
                    done = batch.spoolFile
                } else {
                    queue.addFirst(batch)
                    queuedBytes += batch.bytes
                }
            }
            done?.delete()
            if (gen == generation && retryAfterMs != null) {
                scheduleRetry(retryAfterMs)
                return
            }
        }
    }

    /** Returns null when the batch is done (sent or given up), else how long to wait before retrying. */
    private fun post(batch: Batch): Long? {
        batch.attempts++
        val body = try {
            bodyOf(batch)
        } catch (_: Throwable) {
            // Cannot be encoded: it never will be.
            synchronized(lock) { failed += batch.count }
            failedNow()
            return null
        }
        val response = try {
            http.post(
                url,
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Encoding" to "gzip",
                    "Authorization" to "Bearer $token",
                ),
                body,
                TIMEOUT_MS,
            )
        } catch (_: Throwable) {
            null
        }
        val status = response?.status ?: 0
        if (status in 200..299) {
            synchronized(lock) { sent += batch.count }
            return null
        }
        if (status in PERMANENT_FAILURES) {
            synchronized(lock) { failed += batch.count }
            failedNow()
            return null
        }
        return retryDelay(batch, response?.retryAfterMs)
    }

    private fun bodyOf(batch: Batch): ByteArray {
        batch.body?.let { return it }
        val encoded = encode(batch.segments ?: emptyList())
        synchronized(lock) {
            batch.body = encoded
            // The compressed body is all a retry needs, and smaller.
            batch.segments = null
        }
        return encoded
    }

    private fun retryDelay(batch: Batch, requestedMs: Long?): Long? {
        if (batch.attempts > retryDelaysMs.size) {
            synchronized(lock) { failed += batch.count }
            failedNow()
            return null
        }
        return requestedMs ?: retryDelaysMs[batch.attempts - 1]
    }

    /**
     * Writes every batch that waits or is on its way to [spoolDirectory], on
     * the calling thread, for a process about to die. A spooled batch that
     * is delivered later in this run is removed from the spool. Returns how
     * many batches were written.
     */
    fun spool(): Int {
        val dir = spoolDirectory ?: return 0
        var written = 0
        synchronized(lock) {
            val batches = ArrayList<Batch>(queue.size + 1)
            inFlight?.let { batches.add(it) }
            batches.addAll(queue)
            if (batches.none { it.spoolFile == null }) return 0
            try {
                dir.mkdirs()
            } catch (_: Throwable) {
                return 0
            }
            for (batch in batches) {
                if (batch.spoolFile != null) continue
                try {
                    val body = batch.body ?: encode(batch.segments ?: emptyList())
                    val file = File(dir, "${scheduler.now()}-${spoolSequence++}-${batch.count}$SPOOL_SUFFIX")
                    val tmp = File(file.path + ".tmp")
                    FileOutputStream(tmp).use { out ->
                        out.write(body)
                        try {
                            out.fd.sync()
                        } catch (_: Throwable) {
                        }
                    }
                    if (tmp.renameTo(file)) {
                        batch.spoolFile = file
                        written++
                    } else {
                        tmp.delete()
                    }
                } catch (_: Throwable) {
                    // The next batch may still fit.
                }
            }
        }
        return written
    }

    /**
     * Queues what an earlier run spooled and starts sending it. Expired and
     * half-written files are removed. Blocks on disk; runs once per
     * transport.
     */
    fun loadSpooled() {
        val dir = spoolDirectory ?: return
        synchronized(lock) {
            if (spoolLoaded) return
            spoolLoaded = true
        }
        val files = try {
            dir.listFiles()
        } catch (_: Throwable) {
            null
        } ?: return
        val now = scheduler.now()
        class Spooled(val file: File, val timeMs: Long, val seq: Long, val count: Int)

        val found = ArrayList<Spooled>()
        for (file in files) {
            val name = file.name
            if (name.endsWith(".tmp")) {
                file.delete()
                continue
            }
            if (!name.endsWith(SPOOL_SUFFIX)) continue
            val parts = name.removeSuffix(SPOOL_SUFFIX).split('-')
            val t = parts.getOrNull(0)?.toLongOrNull()
            val seq = parts.getOrNull(1)?.toLongOrNull()
            val count = parts.getOrNull(2)?.toIntOrNull()
            if (t == null || seq == null || count == null || count <= 0 ||
                t < now - SPOOL_MAX_AGE_MS || t > now + SPOOL_MAX_AGE_MS
            ) {
                file.delete()
                continue
            }
            found.add(Spooled(file, t, seq, count))
        }
        found.sortWith(compareBy<Spooled>({ it.timeMs }, { it.seq }))
        for (s in found) {
            val body = try {
                s.file.readBytes()
            } catch (_: Throwable) {
                continue
            }
            if (body.isEmpty()) {
                s.file.delete()
                continue
            }
            enqueue(Batch(null, body, s.count, body.size.toLong(), s.file))
        }
        if (found.isNotEmpty()) kick()
    }
}
