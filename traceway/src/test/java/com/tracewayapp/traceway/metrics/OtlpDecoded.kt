package com.tracewayapp.traceway.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** An OTLP `ExportMetricsServiceRequest` read back from protobuf, for assertions. */
internal class DecodedRequest(val resources: List<DecodedResourceMetrics>)

internal class DecodedResourceMetrics(val attributes: Map<String, Any?>, val scopes: List<DecodedScope>)

internal class DecodedScope(val name: String, val version: String, val metrics: List<DecodedMetric>)

internal class DecodedMetric(
    val name: String,
    val unit: String,
    /** `sum` or `exponentialHistogram`; anything else is `other`. */
    val kind: String,
    val temporality: Int,
    val monotonic: Boolean,
    val sumPoints: List<NumberPoint>,
    val histogramPoints: List<ExpPoint>,
)

internal class NumberPoint(val attributes: Map<String, Any?>, val startNanos: Long, val timeNanos: Long, val value: Double)

internal class ExpPoint(
    val attributes: Map<String, Any?>,
    val startNanos: Long,
    val timeNanos: Long,
    val count: Long,
    val sum: Double,
    val scale: Int,
    val zeroCount: Long,
    val offset: Int,
    val bucketCounts: List<Long>,
    val min: Double?,
    val max: Double?,
    val exemplars: List<DecodedExemplar>,
)

internal class DecodedExemplar(val filteredAttributes: Map<String, Any?>, val timeNanos: Long, val value: Double)

internal const val TEMPORALITY_DELTA = 1

internal object OtlpDecoder {
    fun request(body: ByteArray): DecodedRequest {
        val out = ArrayList<DecodedResourceMetrics>()
        fields(body) { field, r -> if (field == 1) out.add(resourceMetrics(r.readBytes())) else null }
        return DecodedRequest(out)
    }

    /** Calls [each] per field; it reads the value and returns non-null, or returns null to have it skipped. */
    private fun fields(bytes: ByteArray, each: (Int, ProtoReader) -> Any?) {
        val reader = ProtoReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readTag()
            if (each(tag.field, reader) == null) reader.skip(tag.wireType)
        }
    }

    private fun resourceMetrics(bytes: ByteArray): DecodedResourceMetrics {
        var attrs: Map<String, Any?> = emptyMap()
        val scopes = ArrayList<DecodedScope>()
        fields(bytes) { field, r ->
            when (field) {
                1 -> {
                    val list = LinkedHashMap<String, Any?>()
                    fields(r.readBytes()) { f, rr -> if (f == 1) keyValue(rr.readBytes(), list) else null }
                    attrs = list
                }
                2 -> scopes.add(scopeMetrics(r.readBytes()))
                else -> null
            }
        }
        return DecodedResourceMetrics(attrs, scopes)
    }

    private fun scopeMetrics(bytes: ByteArray): DecodedScope {
        var name = ""
        var version = ""
        val metrics = ArrayList<DecodedMetric>()
        fields(bytes) { field, r ->
            when (field) {
                1 -> fields(r.readBytes()) { f, rr ->
                    when (f) {
                        1 -> rr.readString().also { name = it }
                        2 -> rr.readString().also { version = it }
                        else -> null
                    }
                }
                2 -> metrics.add(metric(r.readBytes()))
                else -> null
            }
        }
        return DecodedScope(name, version, metrics)
    }

    private fun metric(bytes: ByteArray): DecodedMetric {
        var name = ""
        var unit = ""
        var kind = "other"
        var temporality = 0
        var monotonic = false
        val sums = ArrayList<NumberPoint>()
        val histograms = ArrayList<ExpPoint>()
        fields(bytes) { field, r ->
            when (field) {
                1 -> r.readString().also { name = it }
                3 -> r.readString().also { unit = it }
                7 -> {
                    kind = "sum"
                    fields(r.readBytes()) { f, rr ->
                        when (f) {
                            1 -> sums.add(numberPoint(rr.readBytes()))
                            2 -> rr.readVarint().also { temporality = it.toInt() }
                            3 -> rr.readVarint().also { monotonic = it != 0L }
                            else -> null
                        }
                    }
                }
                10 -> {
                    kind = "exponentialHistogram"
                    fields(r.readBytes()) { f, rr ->
                        when (f) {
                            1 -> histograms.add(expPoint(rr.readBytes()))
                            2 -> rr.readVarint().also { temporality = it.toInt() }
                            else -> null
                        }
                    }
                }
                else -> null
            }
        }
        return DecodedMetric(name, unit, kind, temporality, monotonic, sums, histograms)
    }

    private fun numberPoint(bytes: ByteArray): NumberPoint {
        val attrs = LinkedHashMap<String, Any?>()
        var start = 0L
        var time = 0L
        var value = 0.0
        fields(bytes) { field, r ->
            when (field) {
                7 -> keyValue(r.readBytes(), attrs)
                2 -> r.readFixed64().also { start = it }
                3 -> r.readFixed64().also { time = it }
                4 -> r.readDouble().also { value = it }
                6 -> r.readFixed64().also { value = it.toDouble() }
                else -> null
            }
        }
        return NumberPoint(attrs, start, time, value)
    }

    private fun expPoint(bytes: ByteArray): ExpPoint {
        val attrs = LinkedHashMap<String, Any?>()
        var start = 0L
        var time = 0L
        var count = 0L
        var sum = 0.0
        var scale = 0
        var zero = 0L
        var offset = 0
        val counts = ArrayList<Long>()
        var min: Double? = null
        var max: Double? = null
        val exemplars = ArrayList<DecodedExemplar>()
        fields(bytes) { field, r ->
            when (field) {
                1 -> keyValue(r.readBytes(), attrs)
                2 -> r.readFixed64().also { start = it }
                3 -> r.readFixed64().also { time = it }
                4 -> r.readFixed64().also { count = it }
                5 -> r.readDouble().also { sum = it }
                6 -> r.readSigned().also { scale = it.toInt() }
                7 -> r.readFixed64().also { zero = it }
                8 -> fields(r.readBytes()) { f, rr ->
                    when (f) {
                        1 -> rr.readSigned().also { offset = it.toInt() }
                        2 -> {
                            val packed = ProtoReader(rr.readBytes())
                            while (packed.hasMore()) counts.add(packed.readVarint())
                            Unit
                        }
                        else -> null
                    }
                }
                11 -> exemplars.add(exemplar(r.readBytes()))
                12 -> r.readDouble().also { min = it }
                13 -> r.readDouble().also { max = it }
                else -> null
            }
        }
        return ExpPoint(attrs, start, time, count, sum, scale, zero, offset, counts, min, max, exemplars)
    }

    private fun exemplar(bytes: ByteArray): DecodedExemplar {
        val attrs = LinkedHashMap<String, Any?>()
        var time = 0L
        var value = 0.0
        fields(bytes) { field, r ->
            when (field) {
                7 -> keyValue(r.readBytes(), attrs)
                2 -> r.readFixed64().also { time = it }
                3 -> r.readDouble().also { value = it }
                else -> null
            }
        }
        return DecodedExemplar(attrs, time, value)
    }

    private fun keyValue(bytes: ByteArray, into: MutableMap<String, Any?>): Unit {
        var key = ""
        var value: Any? = null
        fields(bytes) { field, r ->
            when (field) {
                1 -> r.readString().also { key = it }
                2 -> fields(r.readBytes()) { f, rr ->
                    when (f) {
                        1 -> rr.readString().also { value = it }
                        2 -> rr.readVarint().also { value = it != 0L }
                        3 -> rr.readVarint().also { value = it }
                        4 -> rr.readDouble().also { value = it }
                        else -> null
                    }
                }
                else -> null
            }
        }
        into[key] = value
    }
}

/**
 * What the server would chart from one or more payloads: histogram points
 * and counter sums by `name|dim=value,...`, as the Flutter tests read them.
 */
internal class Decoded(bodies: List<ByteArray>) {
    val requests: List<DecodedRequest> = bodies.map { OtlpDecoder.request(it) }

    private fun key(name: String, attrs: Map<String, Any?>): String {
        val dims = attrs.map { (k, v) -> "$k=$v" }.sorted()
        return if (dims.isEmpty()) name else "$name|${dims.joinToString(",")}"
    }

    fun resource(index: Int = 0): Map<String, Any?> = requests[index].resources.first().attributes

    private val metrics: List<DecodedMetric>
        get() = requests.flatMap { r -> r.resources.flatMap { rm -> rm.scopes.flatMap { it.metrics } } }

    val sums: Map<String, Double>
        get() {
            val out = LinkedHashMap<String, Double>()
            for (m in metrics.filter { it.kind == "sum" }) {
                assertEquals(TEMPORALITY_DELTA, m.temporality)
                assertTrue(m.monotonic)
                for (p in m.sumPoints) {
                    val k = key(m.name, p.attributes)
                    out[k] = (out[k] ?: 0.0) + p.value
                }
            }
            return out
        }

    val histograms: Map<String, ExpPoint>
        get() {
            val out = LinkedHashMap<String, ExpPoint>()
            for (m in metrics.filter { it.kind == "exponentialHistogram" }) {
                assertEquals(TEMPORALITY_DELTA, m.temporality)
                for (p in m.histogramPoints) out[key(m.name, p.attributes)] = p
            }
            return out
        }
}
