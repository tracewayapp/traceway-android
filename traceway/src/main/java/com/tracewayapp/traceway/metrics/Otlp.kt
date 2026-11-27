package com.tracewayapp.traceway.metrics

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.Data
import io.opentelemetry.sdk.metrics.data.DoubleExemplarData
import io.opentelemetry.sdk.metrics.data.DoublePointData
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramData
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.data.SumData
import io.opentelemetry.sdk.resources.Resource
import java.io.ByteArrayOutputStream

internal const val ATTR_SENT_AT = "traceway.sent_at"
internal const val ATTR_RETRY = "traceway.retry"

private fun nanos(ms: Long): Long = (if (ms < 0) 0 else ms) * 1_000_000L

/** OTel attributes of a JSON-like map: strings, booleans, integers and doubles. */
internal fun attributesOf(values: Map<String, Any>): Attributes {
    val builder: AttributesBuilder = Attributes.builder()
    for ((key, value) in values) {
        when (value) {
            is String -> builder.put(key, value)
            is Boolean -> builder.put(key, value)
            is Int -> builder.put(key, value.toLong())
            is Long -> builder.put(key, value)
            is Double -> builder.put(key, value)
            is Float -> builder.put(key, value.toDouble())
            else -> builder.put(key, value.toString())
        }
    }
    return builder.build()
}

/** One metric of a batch, as the SDK's exporters see the ones it aggregates itself. */
private class BatchMetric(
    private val resource: Resource,
    private val scope: InstrumentationScopeInfo,
    private val name: String,
    private val unit: String,
    private val type: MetricDataType,
    private val data: Data<*>,
) : MetricData {
    override fun getResource() = resource
    override fun getInstrumentationScopeInfo() = scope
    override fun getName() = name
    override fun getDescription() = ""
    override fun getUnit() = unit
    override fun getType() = type
    override fun getData(): Data<*> = data
}

/**
 * Renders a batch as an OTLP `ExportMetricsServiceRequest` in protobuf,
 * with the OpenTelemetry SDK's own marshaler: exponential histograms and
 * monotonic sums, both with delta temporality, which is what the server
 * takes from a client (a cumulative series would need an identity per
 * device).
 */
internal fun encodeMetrics(
    batch: MetricBatch,
    resource: Map<String, Any>,
    scopeName: String,
    scopeVersion: String,
): ByteArray {
    val res = Resource.create(attributesOf(resource))
    val scope = InstrumentationScopeInfo.builder(scopeName).setVersion(scopeVersion).build()
    val start = nanos(batch.startMs)
    val end = nanos(batch.endMs)

    val histograms = LinkedHashMap<String, Pair<String, MutableList<ExponentialHistogramPointData>>>()
    for (point in batch.histograms) {
        val h = point.histogram
        val counts: List<Long>
        val offset: Int
        if (h.buckets.isEmpty()) {
            counts = emptyList()
            offset = 0
        } else {
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            for (index in h.buckets.keys) {
                if (index < low) low = index
                if (index > high) high = index
            }
            val filled = LongArray(high - low + 1)
            for ((index, n) in h.buckets) filled[index - low] = n
            counts = filled.asList()
            offset = low
        }
        val exemplars = point.exemplars.map { e ->
            val filtered = LinkedHashMap<String, Any>()
            filtered["session.id"] = e.sessionId
            e.target?.let { filtered["traceway.target"] = it }
            DoubleExemplarData.create(attributesOf(filtered), nanos(e.timeMs), SpanContext.getInvalid(), e.value)
        }
        val hasRange = h.count > 0
        val data = ExponentialHistogramPointData.create(
            HISTOGRAM_SCALE,
            h.sum,
            h.zeroCount,
            hasRange,
            if (hasRange) h.min else 0.0,
            hasRange,
            if (hasRange) h.max else 0.0,
            ExponentialHistogramBuckets.create(HISTOGRAM_SCALE, offset, counts),
            ExponentialHistogramBuckets.create(HISTOGRAM_SCALE, 0, emptyList()),
            start,
            end,
            attributesOf(point.dims),
            exemplars,
        )
        histograms.getOrPut(point.name) { point.unit to ArrayList() }.second.add(data)
    }

    val sums = LinkedHashMap<String, MutableList<DoublePointData>>()
    for (point in batch.sums) {
        sums.getOrPut(point.name) { ArrayList() }
            .add(DoublePointData.create(start, end, attributesOf(point.dims), point.value, emptyList()))
    }

    val metrics = ArrayList<MetricData>(histograms.size + sums.size)
    for ((name, entry) in histograms) {
        metrics.add(
            BatchMetric(
                res, scope, name, entry.first, MetricDataType.EXPONENTIAL_HISTOGRAM,
                ExponentialHistogramData.create(AggregationTemporality.DELTA, entry.second),
            ),
        )
    }
    for ((name, points) in sums) {
        metrics.add(
            BatchMetric(
                res, scope, name, "", MetricDataType.DOUBLE_SUM,
                SumData.createDoubleSumData(true, AggregationTemporality.DELTA, points),
            ),
        )
    }
    val marshaler = MetricsRequestMarshaler.create(metrics)
    val out = ByteArrayOutputStream(marshaler.binarySerializedSize)
    marshaler.writeBinaryTo(out)
    return out.toByteArray()
}

/**
 * The request with every resource restamped for its one resend: the server
 * corrects the device clock from the send time, so a resend states its own
 * `traceway.sent_at`, and `traceway.retry` lets it drop a payload it already
 * has. Everything else is copied byte for byte. Null when [body] is not a
 * request this SDK can read.
 */
internal fun restampForRetry(body: ByteArray, sentAtMs: Long): ByteArray? = try {
    val out = ProtoWriter()
    val request = ProtoReader(body)
    while (request.hasMore()) {
        val from = request.position
        val tag = request.readTag()
        if (tag.field == 1 && tag.wireType == WIRE_LEN) {
            out.bytesField(1, restampResourceMetrics(request.readBytes(), sentAtMs))
        } else {
            request.skip(tag.wireType)
            out.raw(body, from, request.position)
        }
    }
    out.toByteArray()
} catch (_: Throwable) {
    null
}

private fun restampResourceMetrics(bytes: ByteArray, sentAtMs: Long): ByteArray {
    val out = ProtoWriter()
    val reader = ProtoReader(bytes)
    var sawResource = false
    while (reader.hasMore()) {
        val from = reader.position
        val tag = reader.readTag()
        if (tag.field == 1 && tag.wireType == WIRE_LEN && !sawResource) {
            sawResource = true
            out.bytesField(1, restampResource(reader.readBytes(), sentAtMs))
        } else {
            reader.skip(tag.wireType)
            out.raw(bytes, from, reader.position)
        }
    }
    if (!sawResource) out.bytesField(1, restampResource(ByteArray(0), sentAtMs))
    return out.toByteArray()
}

private fun restampResource(bytes: ByteArray, sentAtMs: Long): ByteArray {
    val out = ProtoWriter()
    val reader = ProtoReader(bytes)
    while (reader.hasMore()) {
        val from = reader.position
        val tag = reader.readTag()
        if (tag.field == 1 && tag.wireType == WIRE_LEN) {
            val keyValue = reader.readBytes()
            val key = keyOf(keyValue)
            if (key == ATTR_SENT_AT || key == ATTR_RETRY) continue
            out.raw(bytes, from, reader.position)
        } else {
            reader.skip(tag.wireType)
            out.raw(bytes, from, reader.position)
        }
    }
    out.bytesField(1, keyValue(ATTR_SENT_AT) { it.varintField(3, sentAtMs) })
    out.bytesField(1, keyValue(ATTR_RETRY) { it.varintField(2, 1) })
    return out.toByteArray()
}

private fun keyOf(keyValue: ByteArray): String? {
    val reader = ProtoReader(keyValue)
    while (reader.hasMore()) {
        val tag = reader.readTag()
        if (tag.field == 1 && tag.wireType == WIRE_LEN) return reader.readString()
        reader.skip(tag.wireType)
    }
    return null
}

/** A `KeyValue` whose `AnyValue` [value] writes. */
private fun keyValue(key: String, value: (ProtoWriter) -> Unit): ByteArray {
    val any = ProtoWriter().also(value).toByteArray()
    return ProtoWriter().apply {
        bytesField(1, key.toByteArray(Charsets.UTF_8))
        bytesField(2, any)
    }.toByteArray()
}

// ------------------------------------------------------------ wire format

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_FIXED32 = 5

internal class ProtoTag(val field: Int, val wireType: Int)

/** Reads protobuf wire format; enough to walk an OTLP request. Throws on malformed input. */
internal class ProtoReader(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {
    var position = start
        private set

    fun hasMore(): Boolean = position < end

    fun readTag(): ProtoTag {
        val key = readVarint()
        return ProtoTag((key ushr 3).toInt(), (key and 7).toInt())
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            require(position < end) { "truncated varint" }
            val b = buf[position++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("malformed varint")
    }

    /** A zigzag-encoded `sint32` / `sint64`. */
    fun readSigned(): Long {
        val v = readVarint()
        return (v ushr 1) xor -(v and 1)
    }

    fun readFixed64(): Long {
        require(position + 8 <= end) { "truncated fixed64" }
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[position + i].toLong() and 0xff) shl (8 * i))
        position += 8
        return v
    }

    fun readFixed32(): Int {
        require(position + 4 <= end) { "truncated fixed32" }
        var v = 0
        for (i in 0 until 4) v = v or ((buf[position + i].toInt() and 0xff) shl (8 * i))
        position += 4
        return v
    }

    fun readDouble(): Double = java.lang.Double.longBitsToDouble(readFixed64())

    fun readBytes(): ByteArray {
        val length = readVarint()
        require(length >= 0 && position + length <= end) { "truncated field" }
        val out = buf.copyOfRange(position, position + length.toInt())
        position += length.toInt()
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> readFixed64()
            WIRE_LEN -> {
                val length = readVarint()
                require(length >= 0 && position + length <= end) { "truncated field" }
                position += length.toInt()
            }
            WIRE_FIXED32 -> readFixed32()
            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
    }
}

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    fun varintField(field: Int, value: Long) {
        tag(field, WIRE_VARINT)
        varint(value)
    }

    fun bytesField(field: Int, bytes: ByteArray) {
        tag(field, WIRE_LEN)
        varint(bytes.size.toLong())
        out.write(bytes, 0, bytes.size)
    }

    fun raw(bytes: ByteArray, from: Int, until: Int) = out.write(bytes, from, until - from)

    fun toByteArray(): ByteArray = out.toByteArray()
}
