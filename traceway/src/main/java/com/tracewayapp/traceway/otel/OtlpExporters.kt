package com.tracewayapp.traceway.otel

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.guarded
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.SdkInfo
import com.tracewayapp.traceway.internal.TaskScheduler
import com.tracewayapp.traceway.internal.gzip
import com.tracewayapp.traceway.internal.isTransientFailure
import com.tracewayapp.traceway.persistence.Outbox
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Delays between the attempts of a live export, before a batch that keeps
 * failing goes to the [Outbox].
 */
internal val LIVE_RETRY_DELAYS_MS: List<Long> = listOf(200L, 1000L)

internal const val OTLP_TIMEOUT_MS = 10_000

/** The headers of an OTLP/HTTP protobuf request to [destination], gzipped. */
internal fun otlpHeaders(destination: OtlpDestination): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = SdkInfo.OTLP_USER_AGENT
    headers.putAll(destination.headers)
    headers["Content-Type"] = "application/x-protobuf"
    headers["Content-Encoding"] = "gzip"
    return headers
}

/**
 * Posts one OTLP/HTTP protobuf request to [destination], gzipped, trying
 * again after each of [retryDelaysMs] while the failure may pass. Blocks
 * (the waits included): call it on an I/O thread. Returns the last status
 * code, 0 when no answer came.
 */
internal fun postOtlp(
    http: HttpTransport,
    destination: OtlpDestination,
    body: ByteArray,
    retryDelaysMs: List<Long> = emptyList(),
): Int {
    val compressed = gzip(body)
    val headers = otlpHeaders(destination)
    var attempt = 0
    while (true) {
        val status = postOnce(http, destination.url, headers, compressed)
        if (!isTransientFailure(status) || attempt >= retryDelaysMs.size) return status
        try {
            Thread.sleep(retryDelaysMs[attempt++])
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return status
        }
    }
}

private fun postOnce(http: HttpTransport, url: String, headers: Map<String, String>, body: ByteArray): Int {
    val status = try {
        http.post(url, headers, body, OTLP_TIMEOUT_MS).status
    } catch (_: Throwable) {
        0
    }
    if (status == 401 || status == 403 || status == 404) ServerProblems.rejected(url, status)
    return status
}

/**
 * One warning per process, whatever `debug` says, when a server refuses
 * the SDK's telemetry for good: a wrong token (401, 403) or a server
 * without OTLP (404) would otherwise drop every batch without a word.
 */
internal object ServerProblems {
    private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Where the warning goes; tests replace it. */
    @Volatile
    internal var log: (String) -> Unit = { message ->
        try {
            android.util.Log.w("Traceway", message)
        } catch (_: Throwable) {
        }
    }

    fun rejected(url: String, status: Int) {
        if (warned.get() || !warned.compareAndSet(false, true)) return
        guarded("server problem") { log(message(url, status)) }
    }

    fun message(url: String, status: Int): String {
        val at = url.indexOf("/api/otel/")
        if (at < 0) return "Traceway: the telemetry receiver $url refused a batch (HTTP $status); check TracewayOptions.telemetry"
        val server = url.substring(0, at)
        return if (status == 404) {
            "Traceway: $server does not accept Android 2.x telemetry (HTTP 404 on ${url.substring(at)}). The server needs " +
                "OTLP support; on an older server stay on com.tracewayapp:traceway 1.0.1"
        } else {
            "Traceway: the server rejected the project token (HTTP $status) at $server. Copy the token from the " +
                "project's Connection page into TracewayOptions(token = …)"
        }
    }

    internal fun resetForTest() = warned.set(false)
}

/**
 * An exporter that can write to disk instead of the network, for a process
 * that is about to die (an uncaught exception).
 */
internal interface PersistingExporter {
    /**
     * While true, [LogRecordExporter.export] / [SpanExporter.export] write
     * the batch to the outbox on the calling thread, before they return a
     * completed result, instead of sending it. Switching it on also queues
     * the batches waiting between two attempts. The next start sends what
     * was written.
     */
    var persistOnly: Boolean
}

/**
 * Sends OTLP request bodies of one kind to one destination: live retries
 * ([LIVE_RETRY_DELAYS_MS]) without holding a thread between attempts, a
 * transient failure to the [Outbox], a permanent one dropped. Tracks what
 * is on its way so a flush can wait for it: the batch processor hands a
 * batch over and forgets it.
 */
internal class OtlpSender(
    private val kind: String,
    private val destination: OtlpDestination,
    private val outbox: Outbox?,
    private val http: HttpTransport,
    private val scheduler: TaskScheduler,
    private val retryDelaysMs: List<Long>,
) {
    private class Waiting(val body: ByteArray, val timer: Cancellable)

    private val lock = Any()
    private val inFlight = LinkedHashSet<CompletableResultCode>()
    private val waiting = HashMap<CompletableResultCode, Waiting>()

    @Volatile
    private var shutdown = false

    @Volatile
    private var diskOnly = false

    var persistOnly: Boolean
        get() = diskOnly
        set(value) {
            diskOnly = value
            if (!value) return
            val spilled = synchronized(lock) {
                val all = HashMap(waiting)
                waiting.clear()
                all
            }
            for ((result, w) in spilled) {
                w.timer.cancel()
                complete(result, queue(w.body))
            }
        }

    /** [encode] runs on the calling thread when persisting, on the I/O pool otherwise. */
    fun send(encode: () -> ByteArray): CompletableResultCode {
        if (shutdown) return CompletableResultCode.ofFailure()
        if (diskOnly) return if (persist(encode)) CompletableResultCode.ofSuccess() else CompletableResultCode.ofFailure()
        val result = track()
        scheduler.io {
            val body = try {
                encode()
            } catch (_: Throwable) {
                null
            }
            when {
                body == null -> result.fail()
                diskOnly -> complete(result, queue(body))
                else -> attempt(body, gzip(body), 0, result)
            }
        }
        return result
    }

    /** Writes the batch to the outbox now, on the calling thread. */
    fun persist(encode: () -> ByteArray): Boolean =
        try {
            queue(encode())
        } catch (_: Throwable) {
            false
        }

    private fun attempt(body: ByteArray, compressed: ByteArray, n: Int, result: CompletableResultCode) {
        scheduler.io {
            val status = postOnce(http, destination.url, otlpHeaders(destination), compressed)
            when {
                status in 200..299 -> result.succeed()
                // Rejected for good: sending it again would fail the same way.
                !isTransientFailure(status) -> result.fail()
                n >= retryDelaysMs.size || diskOnly -> complete(result, queue(body))
                else -> {
                    // Checked again under the lock: a switch to persistOnly that
                    // spilled the waiting batches just before must not miss this one.
                    val persistNow = synchronized(lock) {
                        if (diskOnly) {
                            true
                        } else {
                            val timer = scheduler.schedule(retryDelaysMs[n]) {
                                val still = synchronized(lock) { waiting.remove(result) != null }
                                if (still) {
                                    if (diskOnly) complete(result, queue(body)) else attempt(body, compressed, n + 1, result)
                                }
                            }
                            waiting[result] = Waiting(body, timer)
                            false
                        }
                    }
                    if (persistNow) complete(result, queue(body))
                }
            }
        }
    }

    private fun queue(body: ByteArray): Boolean = outbox?.add(kind, body) == true

    private fun complete(result: CompletableResultCode, ok: Boolean) {
        if (ok) result.succeed() else result.fail()
    }

    private fun track(): CompletableResultCode {
        val result = CompletableResultCode()
        synchronized(lock) { inFlight.add(result) }
        result.whenComplete { synchronized(lock) { inFlight.remove(result) } }
        return result
    }

    /** Completes when nothing is on its way any more, requests that started meanwhile included. */
    fun flush(): CompletableResultCode {
        val result = CompletableResultCode()
        settle(result)
        return result
    }

    private fun settle(result: CompletableResultCode) {
        val pending = synchronized(lock) { inFlight.toList() }
        if (pending.isEmpty()) {
            result.succeed()
            return
        }
        CompletableResultCode.ofAll(pending).whenComplete { settle(result) }
    }

    fun shutdown(): CompletableResultCode {
        val result = flush()
        shutdown = true
        return result
    }
}

/**
 * Sends log records as OTLP/HTTP protobuf to one destination, at exactly
 * its URL. A batch that fails for a reason that may pass goes to the
 * [Outbox] instead of being lost.
 */
internal class OtlpLogExporter(
    destination: OtlpDestination,
    outbox: Outbox?,
    http: HttpTransport,
    scheduler: TaskScheduler,
    retryDelaysMs: List<Long> = LIVE_RETRY_DELAYS_MS,
) : LogRecordExporter, PersistingExporter {
    private val sender = OtlpSender(OtlpEncoding.KIND_LOGS, destination, outbox, http, scheduler, retryDelaysMs)

    override var persistOnly: Boolean
        get() = sender.persistOnly
        set(value) {
            sender.persistOnly = value
        }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (logs.isEmpty()) return CompletableResultCode.ofSuccess()
        // The processor reuses its batch once the result completes or times out.
        val batch = ArrayList(logs)
        return sender.send { OtlpEncoding.logsRequest(batch) }
    }

    /** Writes [logs] to the outbox now, on the calling thread. */
    fun persist(logs: Collection<LogRecordData>): Boolean =
        logs.isEmpty() || sender.persist { OtlpEncoding.logsRequest(logs) }

    override fun flush(): CompletableResultCode = sender.flush()

    override fun shutdown(): CompletableResultCode = sender.shutdown()
}

/** The span counterpart of [OtlpLogExporter]. */
internal class OtlpSpanExporter(
    destination: OtlpDestination,
    outbox: Outbox?,
    http: HttpTransport,
    scheduler: TaskScheduler,
    retryDelaysMs: List<Long> = LIVE_RETRY_DELAYS_MS,
) : SpanExporter, PersistingExporter {
    private val sender = OtlpSender(OtlpEncoding.KIND_TRACES, destination, outbox, http, scheduler, retryDelaysMs)

    override var persistOnly: Boolean
        get() = sender.persistOnly
        set(value) {
            sender.persistOnly = value
        }

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        if (spans.isEmpty()) return CompletableResultCode.ofSuccess()
        val batch = ArrayList(spans)
        return sender.send { OtlpEncoding.tracesRequest(batch) }
    }

    /** Writes [spans] to the outbox now, on the calling thread. */
    fun persist(spans: Collection<SpanData>): Boolean =
        spans.isEmpty() || sender.persist { OtlpEncoding.tracesRequest(spans) }

    override fun flush(): CompletableResultCode = sender.flush()

    override fun shutdown(): CompletableResultCode = sender.shutdown()
}

/**
 * Sends what [Events.isTracewayOnly] selects (exceptions, messages and the
 * SDK's control records) to [traceway], everything else to [inner]: used
 * when ordinary logs go to another receiver. Runs after the ring buffer, so
 * a record flushed by a trigger is routed the same as one streamed live.
 */
internal class RoutingLogExporter(
    val inner: LogRecordExporter,
    val traceway: LogRecordExporter,
) : LogRecordExporter, PersistingExporter {

    override var persistOnly: Boolean
        get() = (traceway as? PersistingExporter)?.persistOnly ?: false
        set(value) {
            (traceway as? PersistingExporter)?.persistOnly = value
            (inner as? PersistingExporter)?.persistOnly = value
        }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        val own = ArrayList<LogRecordData>()
        val rest = ArrayList<LogRecordData>()
        for (record in logs) {
            (if (Events.isTracewayOnly(record.eventName)) own else rest).add(record)
        }
        val results = ArrayList<CompletableResultCode>(2)
        if (own.isNotEmpty()) results.add(guarded("routing export", FAILED) { traceway.export(own) })
        if (rest.isNotEmpty()) results.add(guarded("routing export", FAILED) { inner.export(rest) })
        return if (results.isEmpty()) CompletableResultCode.ofSuccess() else CompletableResultCode.ofAll(results)
    }

    override fun flush(): CompletableResultCode =
        CompletableResultCode.ofAll(listOf(guarded("routing flush", FAILED) { traceway.flush() }, guarded("routing flush", FAILED) { inner.flush() }))

    override fun shutdown(): CompletableResultCode =
        CompletableResultCode.ofAll(listOf(guarded("routing shutdown", FAILED) { traceway.shutdown() }, guarded("routing shutdown", FAILED) { inner.shutdown() }))

    private companion object {
        /** What an export that threw counts as. */
        val FAILED: CompletableResultCode = CompletableResultCode.ofFailure()
    }
}
