package com.tracewayapp.traceway.otel

import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.gunzip
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.Value
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.testing.trace.TestSpanData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData

/** One request a [FakeHttp] saw. */
internal class SeenRequest(val url: String, val headers: Map<String, String>, val body: ByteArray) {
    /** The body, gunzipped when it was sent compressed. */
    val plain: ByteArray get() = if (headers["Content-Encoding"] == "gzip") gunzip(body) else body
}

/** Answers every request with [handler]; records them all. Thread-safe. */
internal class FakeHttp(var handler: (SeenRequest) -> HttpResponse = { HttpResponse(200) }) : HttpTransport {
    private val seen = ArrayList<SeenRequest>()

    val requests: List<SeenRequest> get() = synchronized(seen) { ArrayList(seen) }

    fun to(url: String): List<SeenRequest> = requests.filter { it.url == url }

    fun clear() = synchronized(seen) { seen.clear() }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse {
        val request = SeenRequest(url, headers, body)
        synchronized(seen) { seen.add(request) }
        return handler(request)
    }

    companion object {
        /** Answers with [statuses] in turn, then [then]. */
        fun sequence(vararg statuses: Int, then: Int = 200): FakeHttp {
            val queue = ArrayDeque(statuses.toList())
            return FakeHttp { HttpResponse(synchronized(queue) { queue.removeFirstOrNull() } ?: then) }
        }
    }
}

internal val TEST_RESOURCE: Resource = Resource.create(
    Attributes.of(AttributeKey.stringKey("service.name"), "test-app", AttributeKey.stringKey("service.version"), "1.0"),
)

internal val TEST_SCOPE: InstrumentationScopeInfo = InstrumentationScopeInfo.builder("traceway-android").setVersion("2.0.0").build()

/** A log record snapshot, as the SDK would hand to an exporter. */
internal class TestLog(
    private val text: String,
    private val event: String? = null,
    private val atMs: Long = 1_700_000_000_000L,
    private val attrs: Attributes = Attributes.empty(),
    private val res: Resource = TEST_RESOURCE,
) : LogRecordData {
    override fun getResource(): Resource = res
    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = TEST_SCOPE
    override fun getTimestampEpochNanos(): Long = atMs * 1_000_000
    override fun getObservedTimestampEpochNanos(): Long = atMs * 1_000_000
    override fun getSpanContext(): SpanContext = SpanContext.getInvalid()
    override fun getSeverity(): Severity = Severity.INFO
    override fun getSeverityText(): String = "INFO"

    @Deprecated("Deprecated in Java")
    override fun getBody(): Body = Body.string(text)
    override fun getBodyValue(): Value<*> = Value.of(text)
    override fun getAttributes(): Attributes = attrs
    override fun getTotalAttributeCount(): Int = attrs.size()
    override fun getEventName(): String? = event
}

internal fun testSpan(name: String, endMs: Long = 1_700_000_000_000L, resource: Resource = TEST_RESOURCE): SpanData =
    TestSpanData.builder()
        .setName(name)
        .setKind(SpanKind.CLIENT)
        .setSpanContext(
            SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                TraceFlags.getSampled(),
                TraceState.getDefault(),
            ),
        )
        .setStatus(StatusData.ok())
        .setStartEpochNanos((endMs - 50) * 1_000_000)
        .setEndEpochNanos(endMs * 1_000_000)
        .setHasEnded(true)
        .setTotalRecordedEvents(0)
        .setTotalRecordedLinks(0)
        .setResource(resource)
        .setInstrumentationScopeInfo(TEST_SCOPE)
        .build()

/** A decoded OTLP log record, with what tests look at. */
internal class DecodedLog(
    val eventName: String,
    val body: String?,
    val attributes: Map<String, String>,
    val resource: Map<String, String>,
)

internal class DecodedSpan(val name: String, val resource: Map<String, String>)

/** A tiny protobuf reader for OTLP requests, enough for assertions. */
internal object Otlp {
    private class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    private fun fields(buf: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < buf.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            when (val type = (tag and 7).toInt()) {
                0 -> out.add(Field(number, type, varint(), null))
                1 -> {
                    out.add(Field(number, type, 0, buf.copyOfRange(pos, pos + 8)))
                    pos += 8
                }
                2 -> {
                    val len = varint().toInt()
                    out.add(Field(number, type, 0, buf.copyOfRange(pos, pos + len)))
                    pos += len
                }
                5 -> {
                    out.add(Field(number, type, 0, buf.copyOfRange(pos, pos + 4)))
                    pos += 4
                }
                else -> error("wire type $type")
            }
        }
        return out
    }

    private fun messages(buf: ByteArray, number: Int): List<ByteArray> =
        fields(buf).filter { it.number == number && it.wireType == 2 }.map { it.bytes!! }

    private fun string(buf: ByteArray, number: Int): String? =
        fields(buf).lastOrNull { it.number == number && it.wireType == 2 }?.bytes?.toString(Charsets.UTF_8)

    /** KeyValue list as strings: string, bool, int and double values (AnyValue fields 1-4). */
    private fun keyValues(buf: ByteArray, number: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (kv in messages(buf, number)) {
            val key = string(kv, 1) ?: continue
            val any = messages(kv, 2).lastOrNull() ?: continue
            val field = fields(any).lastOrNull() ?: continue
            out[key] = when (field.number) {
                1 -> field.bytes!!.toString(Charsets.UTF_8)
                2 -> (field.varint != 0L).toString()
                3 -> field.varint.toString()
                4 -> java.nio.ByteBuffer.wrap(field.bytes!!).order(java.nio.ByteOrder.LITTLE_ENDIAN).double.toString()
                else -> continue
            }
        }
        return out
    }

    /** How many resource containers the request has. */
    fun resourceCount(body: ByteArray): Int = messages(body, 1).size

    fun logs(body: ByteArray): List<DecodedLog> {
        val out = ArrayList<DecodedLog>()
        for (resourceLogs in messages(body, 1)) {
            val resource = messages(resourceLogs, 1).lastOrNull()?.let { keyValues(it, 1) } ?: emptyMap()
            for (scopeLogs in messages(resourceLogs, 2)) {
                for (record in messages(scopeLogs, 2)) {
                    out.add(
                        DecodedLog(
                            eventName = string(record, 12) ?: "",
                            body = messages(record, 5).lastOrNull()?.let { string(it, 1) },
                            attributes = keyValues(record, 6),
                            resource = resource,
                        ),
                    )
                }
            }
        }
        return out
    }

    fun spans(body: ByteArray): List<DecodedSpan> {
        val out = ArrayList<DecodedSpan>()
        for (resourceSpans in messages(body, 1)) {
            val resource = messages(resourceSpans, 1).lastOrNull()?.let { keyValues(it, 1) } ?: emptyMap()
            for (scopeSpans in messages(resourceSpans, 2)) {
                for (span in messages(scopeSpans, 2)) out.add(DecodedSpan(string(span, 5) ?: "", resource))
            }
        }
        return out
    }
}
