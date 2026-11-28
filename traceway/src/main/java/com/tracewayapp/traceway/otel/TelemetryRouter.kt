package com.tracewayapp.traceway.otel

import com.tracewayapp.traceway.core.BufferEntry
import com.tracewayapp.traceway.core.ControlState
import com.tracewayapp.traceway.core.FieldLookup
import com.tracewayapp.traceway.core.Ids
import com.tracewayapp.traceway.core.Mode
import com.tracewayapp.traceway.core.RingBuffer
import com.tracewayapp.traceway.internal.Json
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.Value
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.TimeUnit
import kotlin.random.Random

internal enum class Routed { STREAM, BUFFER, OFF, FILTERED }

internal class ObservedRecord(
    val signal: String,
    val timeMs: Long,
    val lookup: FieldLookup,
    val item: TelemetryItem,
    /** Whether the record was sent, buffered or dropped. Triggers still see dropped records. */
    val routed: Routed,
)

internal interface RouterHooks {
    val sessionId: String?
    fun globalAttribute(key: String): String?
    fun resourceAttribute(key: String): String?

    /** Adds what the SDK knows to a record; returning false drops it. */
    fun enrichLog(record: ReadWriteLogRecord, signal: String): Boolean
    fun observe(record: ObservedRecord)
}

private val SPAN_STATUS = mapOf(StatusCode.UNSET to "unset", StatusCode.OK to "ok", StatusCode.ERROR to "error")

/** Records per export of a buffer flush, as many as a batch processor sends. */
private const val FLUSH_BATCH_SIZE = 512
private val BASELINE_SIGNALS = setOf("network", "console", "navigation", "userActions", "custom")

private val ATTR_PREVIOUS_RUN = AttributeKey.booleanKey("traceway.previous_run")
private val ATTR_SESSION_ID = AttributeKey.stringKey(Events.ATTR_SESSION_ID)
private val ATTR_CAPTURE_IDS = AttributeKey.stringKey(Events.ATTR_CAPTURE_IDS)
private val ATTR_INTERACTION = AttributeKey.booleanKey("traceway.interaction")

/** The value of the attribute named [key], whatever its type. */
internal fun Attributes?.valueOf(key: String): Any? {
    if (this == null || isEmpty) return null
    var found: Any? = null
    forEach { k, v -> if (found == null && k.key == key) found = v }
    return found
}

private fun estimateBytes(attributes: Attributes?, body: Any?): Int {
    var size = 200
    if (body != null) size += 2 * body.toString().length
    attributes?.forEach { k, v -> size += 2 * (k.key.length + v.toString().length) + 16 }
    return size
}

private fun bodyText(value: Value<*>?): String? = when (value) {
    null -> null
    else -> value.asString()
}

/**
 * Decides for every log record and span whether it is sent now, kept in the
 * ring buffer or dropped, according to the [ControlState]. Triggers observe
 * a record after it was routed, so a flush they start includes it.
 *
 * Called with the client's lock held (the processors take it), so it needs
 * no synchronization of its own.
 */
internal class TelemetryRouter(
    private val control: ControlState,
    private val buffer: RingBuffer<TelemetryItem>,
    /** The batch processors that stream, owned by the router: the provider's only processors are the control ones. */
    private val logSink: LogRecordProcessor,
    private val spanSink: SpanProcessor,
    /** The exporters behind [logSink] and [spanSink], for [flushBuffered]. */
    private val logExporter: LogRecordExporter,
    private val spanExporter: SpanExporter,
    private val hooks: RouterHooks,
    /**
     * Writes an issue to disk before it is sent: the app may not live to see
     * the answer. True when it took the issue; it then calls its second
     * argument once, on any thread, when the issue is on disk and its send
     * was started.
     */
    private val persistIssue: ((LogRecordData, () -> Unit) -> Boolean)? = null,
    /**
     * While true (the app is crashing), a streamed issue is written to disk
     * before its triggers run: they flush the buffer and assemble a clip,
     * and the issue itself is the one record that must survive.
     */
    private val persistFirst: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val flushedLogs = ArrayList<LogRecordData>()
    private val flushedSpans = ArrayList<SpanData>()
    private val inFlight = ArrayList<CompletableResultCode>()

    /** Records whose triggers are running, one inside the other (an `emit` action re-enters). */
    private var observing = 0

    /**
     * The dashboard's captures running now. Captures a trigger started on
     * the device are not listed: the server tracks the dashboard's ids only.
     */
    private fun activeCaptureIds(): List<String> {
        val t = now()
        return control.settings.captures.filter { it.untilMs > t }.map { it.id }
    }

    fun routeLog(record: ReadWriteLogRecord) {
        val signal = Events.classifyLog(record.eventName)
        val sessionId = hooks.sessionId
        val previousRun = record.getAttribute(ATTR_PREVIOUS_RUN) == true
        if (!previousRun && sessionId != null && record.getAttribute(ATTR_SESSION_ID) == null) {
            record.setAttribute(ATTR_SESSION_ID, sessionId)
        }
        if (!previousRun && signal != Events.SIGNAL_CONTROL) {
            val captureIds = activeCaptureIds()
            if (captureIds.isNotEmpty()) record.setAttribute(ATTR_CAPTURE_IDS, Json.stringify(captureIds))
        }
        if (!hooks.enrichLog(record, signal)) return

        val item = TelemetryItem.Log(record)
        val timeMs = logTimeMs(record)
        val minSeverity = control.settings.signal("console").minSeverity
        val routed = decide(signal, item, timeMs) {
            if (signal != "console" || minSeverity == null) return@decide true
            val severity = record.severity
            severity == null || severity.severityNumber == 0 ||
                severity.severityNumber >= Events.severityNumberOf(minSeverity)
        }
        val persisted = routed == Routed.STREAM && isIssue(record.eventName) && persistFirst() &&
            persistedAsIssue(record.toLogRecordData())
        observeAndSend(ObservedRecord(signal, timeMs, logLookup(record, signal), item, routed), sent = persisted)
    }

    /**
     * Triggers see a record before it is handed over: the exporters get a
     * copy, and what the triggers set on it (the replay status) would be
     * missing. A buffered record is already in the buffer then, so a flush it
     * triggers includes it. What the triggers of a streamed record flush
     * is exported after that record was handed over, as the browser SDK
     * does: the server admits a limited number of log records per device
     * and time window, and an issue must not be the record left out because
     * its own breadcrumbs used them up.
     */
    private fun observeAndSend(observed: ObservedRecord, sent: Boolean = false) {
        observing++
        try {
            hooks.observe(observed)
        } finally {
            observing--
            // A trigger that fails (out of memory while crashing, say) must not cost the record itself.
            if (observed.routed == Routed.STREAM && !sent) send(observed.item, leading = observing == 0)
            if (observing == 0) exportFlushed()
        }
    }

    fun stampSpan(span: ReadWriteSpan) {
        val sessionId = hooks.sessionId
        if (sessionId != null) span.setAttribute(Events.ATTR_SESSION_ID, sessionId)
        val captureIds = activeCaptureIds()
        if (captureIds.isNotEmpty()) span.setAttribute(Events.ATTR_CAPTURE_IDS, Json.stringify(captureIds))
        val baselineRate = control.settings.baselineSampleRate
        span.setAttribute(
            "traceway.collection.source",
            if (sessionId != null && Ids.unitHash(sessionId) < baselineRate) "baseline" else "diagnostic",
        )
        span.setAttribute("traceway.collection.baseline_rate", baselineRate)
    }

    fun routeSpan(span: SpanData) {
        val item = TelemetryItem.Span(span)
        val timeMs = if (span.endEpochNanos > 0) span.endEpochNanos / 1_000_000 else now()
        // A failed interaction goes with the exception that failed it, as the browser SDK sends it,
        // so the issue links to its trace.
        val failedInteraction = span.attributes.get(ATTR_INTERACTION) == true && span.status.statusCode == StatusCode.ERROR
        val routed = decide("network", item, timeMs, forceStream = failedInteraction) { true }
        observeAndSend(ObservedRecord("network", timeMs, spanLookup(span), item, routed))
    }

    /** Sends a record that was dropped when it arrived but whose signal streams now, because its trigger started a capture. */
    fun sendIfStreaming(observed: ObservedRecord) {
        if (observed.routed != Routed.OFF || observed.signal == Events.SIGNAL_CONTROL) return
        if (control.modeFor(observed.signal) == Mode.STREAM) send(observed.item)
    }

    /**
     * Sends what the buffer holds in the window. `signals == null` selects
     * every signal. Returns the number of records taken.
     *
     * They go straight to the exporters, a batch after the other: the batch
     * processors' queues are bounded and drop what does not fit, and a full
     * buffer holds more than they do. The newest breadcrumbs, the ones right
     * before the error, would be the ones lost.
     */
    fun flushBuffered(sinceMs: Long, signals: Set<String>?, untilMs: Long? = null): Int {
        val entries = buffer.take(sinceMs, signals, untilMs)
        for (entry in entries) {
            if (!control.canExport(entry.signal)) continue
            when (val item = entry.item) {
                is TelemetryItem.Log -> {
                    val data = item.record
                    if (!persistedAsIssue(data)) flushedLogs.add(data)
                }
                is TelemetryItem.Span -> flushedSpans.add(item.span)
            }
        }
        // Inside a record's triggers the export waits for that record: see observeAndSend.
        if (observing == 0) exportFlushed()
        return entries.size
    }

    private fun exportFlushed() {
        if (flushedLogs.isEmpty() && flushedSpans.isEmpty()) return
        val logs = ArrayList(flushedLogs)
        val spans = ArrayList(flushedSpans)
        flushedLogs.clear()
        flushedSpans.clear()
        export(logs, spans)
    }

    /** Exports [logs] and [spans], a batch after the other; completes when every export did. Any thread. */
    private fun export(logs: MutableList<LogRecordData>, spans: MutableList<SpanData>): CompletableResultCode {
        val results = ArrayList<CompletableResultCode>()
        while (logs.isNotEmpty()) {
            val batch = takeBatch(logs)
            runCatching { logExporter.export(batch) }.getOrNull()?.let { results.add(it) }
        }
        while (spans.isNotEmpty()) {
            val batch = takeBatch(spans)
            runCatching { spanExporter.export(batch) }.getOrNull()?.let { results.add(it) }
        }
        for (result in results) track(result)
        return CompletableResultCode.ofAll(results)
    }

    /**
     * Hands [issue] over ahead of what its triggers flushed. When it is
     * written to the outbox, the flushed records are exported once it is
     * there and its send was started; otherwise it leaves as the first
     * record of their first export.
     */
    private fun sendBeforeFlushed(issue: LogRecordData) {
        val logs = ArrayList(flushedLogs)
        val spans = ArrayList(flushedSpans)
        flushedLogs.clear()
        flushedSpans.clear()
        // What settle() waits for until the exports below were started and are done.
        val exported = CompletableResultCode()
        track(exported)
        val follow = { export(logs, spans).whenComplete { exported.succeed() } }
        val persisted = try {
            persistIssue?.invoke(issue) { follow() } == true
        } catch (_: Throwable) {
            false
        }
        if (!persisted) {
            logs.add(0, issue)
            follow()
        }
    }

    private fun track(code: CompletableResultCode?) {
        if (code == null || code.isDone) return
        synchronized(inFlight) { inFlight.add(code) }
        code.whenComplete { synchronized(inFlight) { inFlight.remove(code) } }
    }

    /** Waits up to [timeoutMs] for the exports [flushBuffered] started. Call without the client's lock. */
    fun settle(timeoutMs: Long) {
        val pending = synchronized(inFlight) { ArrayList(inFlight) }
        if (pending.isEmpty()) return
        CompletableResultCode.ofAll(pending).join(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun <T> takeBatch(from: MutableList<T>): List<T> {
        val count = minOf(FLUSH_BATCH_SIZE, from.size)
        val batch = ArrayList(from.subList(0, count))
        from.subList(0, count).clear()
        return batch
    }

    /** Decides what happens to a record; a buffered one is stored here, a streamed one is sent once triggers saw it. */
    private inline fun decide(
        signal: String,
        item: TelemetryItem,
        timeMs: Long,
        forceStream: Boolean = false,
        accept: () -> Boolean,
    ): Routed {
        if (signal == Events.SIGNAL_CONTROL) return if (control.enabled) Routed.STREAM else Routed.OFF
        val mode = control.modeFor(signal)
        val sampleRate = control.sampleRate(signal)
        if (!accept()) return Routed.FILTERED
        if (sampleRate < 1 && random() >= sampleRate) {
            if (com.tracewayapp.traceway.Guard.debug && (signal == "errors" || signal == "messages")) notSampled(signal, sampleRate)
            return Routed.FILTERED
        }
        if (mode == Mode.OFF) return Routed.OFF
        val sessionId = hooks.sessionId
        val baselineSampled = signal in BASELINE_SIGNALS &&
            sessionId != null &&
            Ids.unitHash(sessionId) < control.settings.baselineSampleRate
        val forced = forceStream && control.canExport(signal)
        if (mode == Mode.STREAM || baselineSampled || forced) return Routed.STREAM
        val bytes = when (item) {
            is TelemetryItem.Log -> item.mutable?.let { estimateBytes(it.attributes, bodyText(it.bodyValue)) }
                ?: item.record.let { estimateBytes(it.attributes, bodyText(it.bodyValue)) }
            is TelemetryItem.Span -> estimateBytes(item.span.attributes, item.span.name)
        }
        buffer.push(BufferEntry(timeMs, signal, bytes, item))
        return Routed.BUFFER
    }

    private fun isIssue(eventName: String?) = eventName == Events.EXCEPTION || eventName == Events.MESSAGE

    /** With `debug`: an exception or a message the sample rate left out, so a test capture that never arrives says why. */
    private fun notSampled(signal: String, sampleRate: Double) {
        try {
            android.util.Log.d("Traceway", "dropped ${if (signal == "errors") "an exception" else "a message"}: not sampled (sampleRate $sampleRate)")
        } catch (_: Throwable) {
        }
    }

    /** An issue written to disk first is sent from there; the outbox drains it. */
    private fun persistedAsIssue(data: LogRecordData): Boolean =
        isIssue(data.eventName) && persistIssue?.invoke(data) { } == true

    /** [leading]: the record's triggers just ran, and what they flushed has not been exported yet. */
    private fun send(item: TelemetryItem, leading: Boolean = false) {
        when (item) {
            is TelemetryItem.Log -> {
                val live = item.mutable
                if (live != null && isIssue(live.eventName)) {
                    val data = live.toLogRecordData()
                    if (leading && (flushedLogs.isNotEmpty() || flushedSpans.isNotEmpty())) {
                        sendBeforeFlushed(data)
                        return
                    }
                    if (persistedAsIssue(data)) return
                }
                if (live != null) {
                    logSink.onEmit(Context.root(), live)
                } else {
                    track(runCatching { logExporter.export(listOf(item.record)) }.getOrNull())
                }
            }
            is TelemetryItem.Span -> {
                spanSink.onEnd(EndedSpan(item.span))
            }
        }
    }

    private fun logTimeMs(record: ReadWriteLogRecord): Long {
        val nanos = record.timestampEpochNanos.takeIf { it > 0 } ?: record.observedTimestampEpochNanos
        return if (nanos > 0) nanos / 1_000_000 else now()
    }

    private fun logLookup(record: ReadWriteLogRecord, signal: String): FieldLookup = lookup@{ key ->
        when (key) {
            "event.name" -> record.eventName
            "signal" -> signal
            "severity" -> record.severity?.severityNumber?.takeIf { it > 0 }?.toLong()
            "severity.text" -> record.severityText
            "body" -> bodyText(record.bodyValue)
            else -> prefixed(key, record.attributes)
        }
    }

    private fun spanLookup(span: SpanData): FieldLookup = lookup@{ key ->
        when (key) {
            "signal" -> "network"
            "span.name" -> span.name
            "span.kind" -> span.kind.name.lowercase()
            "span.status" -> SPAN_STATUS[span.status.statusCode] ?: "unset"
            "duration_ms" -> if (span.endEpochNanos > 0) (span.endEpochNanos - span.startEpochNanos) / 1e6 else null
            else -> prefixed(key, span.attributes)
        }
    }

    private fun prefixed(key: String, attributes: Attributes?): Any? = when {
        key.startsWith("attributes.") -> attributes.valueOf(key.substring("attributes.".length))
        key.startsWith("resource.") -> hooks.resourceAttribute(key.substring("resource.".length))
        key.startsWith("global.") -> hooks.globalAttribute(key.substring("global.".length))
        key == Events.ATTR_SESSION_ID -> attributes.valueOf(Events.ATTR_SESSION_ID) ?: hooks.sessionId
        else -> null
    }
}

/**
 * The only log processor on the SDK's provider: hands every record to the
 * router, which owns the real batch processor. Takes the client's [lock].
 */
internal class ControlLogProcessor(
    private val lock: Any,
    private val router: () -> TelemetryRouter?,
    private val sink: LogRecordProcessor,
) : LogRecordProcessor {
    override fun onEmit(context: Context, logRecord: ReadWriteLogRecord) {
        try {
            synchronized(lock) { router()?.routeLog(logRecord) }
        } catch (_: Throwable) {
            // Telemetry must never break the app that produced it.
        }
    }

    override fun forceFlush(): CompletableResultCode = sink.forceFlush()
    override fun shutdown(): CompletableResultCode = sink.shutdown()
}

internal class ControlSpanProcessor(
    private val lock: Any,
    private val router: () -> TelemetryRouter?,
    private val sink: SpanProcessor,
) : SpanProcessor {
    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        try {
            synchronized(lock) { router()?.stampSpan(span) }
        } catch (_: Throwable) {
        }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        try {
            val data = span.toSpanData()
            synchronized(lock) { router()?.routeSpan(data) }
        } catch (_: Throwable) {
        }
    }

    override fun isEndRequired(): Boolean = true

    override fun forceFlush(): CompletableResultCode = sink.forceFlush()
    override fun shutdown(): CompletableResultCode = sink.shutdown()
}

/** An ended span as the batch processor takes it: its data, already final. */
internal class EndedSpan(private val data: SpanData) : ReadableSpan {
    override fun getSpanContext() = data.spanContext
    override fun getParentSpanContext() = data.parentSpanContext
    override fun getName(): String = data.name
    override fun toSpanData(): SpanData = data

    @Deprecated("Deprecated in OpenTelemetry")
    @Suppress("DEPRECATION")
    override fun getInstrumentationLibraryInfo() = data.instrumentationLibraryInfo
    override fun getInstrumentationScopeInfo() = data.instrumentationScopeInfo
    override fun hasEnded(): Boolean = true
    override fun getLatencyNanos(): Long = data.endEpochNanos - data.startEpochNanos
    override fun getKind() = data.kind
    override fun <T : Any?> getAttribute(key: AttributeKey<T>): T? = data.attributes.get(key)
    override fun getAttributes(): Attributes = data.attributes
}
