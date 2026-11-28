package com.tracewayapp.traceway.otel

import io.opentelemetry.api.common.Value
import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.SpanData
import java.io.ByteArrayOutputStream

/**
 * OTLP/HTTP protobuf request bodies, built with the OpenTelemetry Java
 * marshalers, and the little protobuf wire handling the SDK needs on top:
 * splitting a request into its resource containers and putting containers
 * of several requests back together, without decoding the records.
 *
 * `ExportLogsServiceRequest` and `ExportTraceServiceRequest` have the same
 * shape at the top: repeated `ResourceLogs` / `ResourceSpans` in field 1,
 * each holding its `Resource` in field 1, its scopes (and their records)
 * in field 2 and a schema URL in field 3.
 */
internal object OtlpEncoding {
    const val KIND_LOGS = "logs"
    const val KIND_TRACES = "traces"

    /** An `ExportLogsServiceRequest` holding [records]. */
    fun logsRequest(records: Collection<LogRecordData>): ByteArray {
        val marshaler = LogsRequestMarshaler.create(records)
        val out = ByteArrayOutputStream(maxOf(64, marshaler.binarySerializedSize))
        marshaler.writeBinaryTo(out)
        return out.toByteArray()
    }

    /** An `ExportTraceServiceRequest` holding [spans]. */
    fun tracesRequest(spans: Collection<SpanData>): ByteArray {
        val marshaler = TraceRequestMarshaler.create(spans)
        val out = ByteArrayOutputStream(maxOf(64, marshaler.binarySerializedSize))
        marshaler.writeBinaryTo(out)
        return out.toByteArray()
    }

    /** [record] without its resource, so a request of it holds only the record and its scope. */
    fun withoutResource(record: LogRecordData): LogRecordData = ResourcelessLog(record)

    /** [span] without its resource. */
    fun withoutResource(span: SpanData): SpanData = ResourcelessSpan(span)

    /**
     * One resource container of a request: [prefix] is every field but the
     * scopes (the resource and its schema URL) as raw fields, [scopes] the
     * raw scope fields. Containers with the same prefix can be merged by
     * concatenating their scopes.
     */
    class Container(val prefix: ByteArray, val scopes: ByteArray)

    /** The resource containers of an OTLP logs or traces request. Throws on a body that is not protobuf. */
    fun containers(body: ByteArray): List<Container> {
        val out = ArrayList<Container>(1)
        val top = WireReader(body, 0, body.size)
        while (!top.atEnd()) {
            val tag = top.varint()
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            if (field != 1 || wireType != WIRE_LEN) {
                top.skip(wireType)
                continue
            }
            val length = top.length()
            val end = top.pos + length
            val prefix = ByteArrayOutputStream()
            val scopes = ByteArrayOutputStream()
            val inner = WireReader(body, top.pos, end)
            while (!inner.atEnd()) {
                val start = inner.pos
                val innerTag = inner.varint()
                inner.skip((innerTag and 7).toInt())
                val target = if ((innerTag ushr 3).toInt() == 2) scopes else prefix
                target.write(body, start, inner.pos - start)
            }
            top.pos = end
            out.add(Container(prefix.toByteArray(), scopes.toByteArray()))
        }
        return out
    }

    /** A request with one resource container per group: the group's prefix, then all its scopes. */
    fun request(groups: List<Pair<ByteArray, List<ByteArray>>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((prefix, scopes) in groups) {
            var size = prefix.size
            for (s in scopes) size += s.size
            writeVarint(out, (1L shl 3) or WIRE_LEN.toLong())
            writeVarint(out, size.toLong())
            out.write(prefix)
            for (s in scopes) out.write(s)
        }
        return out.toByteArray()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_I64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_I32 = 5

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private class WireReader(private val buf: ByteArray, var pos: Int, private val end: Int) {
        fun atEnd() = pos >= end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }

        fun length(): Int {
            val n = varint()
            require(n >= 0 && n <= end - pos) { "bad length" }
            return n.toInt()
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> varint()
                WIRE_I64 -> advance(8)
                WIRE_LEN -> advance(length())
                WIRE_I32 -> advance(4)
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }

        private fun advance(n: Int) {
            require(n <= end - pos) { "truncated field" }
            pos += n
        }
    }

    private class ResourcelessLog(private val d: LogRecordData) : LogRecordData by d {
        override fun getResource(): Resource = Resource.empty()
        override fun getEventName(): String? = d.eventName
        override fun getBodyValue(): Value<*>? = d.bodyValue
        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = d.instrumentationScopeInfo
    }

    private class ResourcelessSpan(private val d: SpanData) : SpanData by d {
        override fun getResource(): Resource = Resource.empty()
        override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = d.instrumentationScopeInfo
    }
}
