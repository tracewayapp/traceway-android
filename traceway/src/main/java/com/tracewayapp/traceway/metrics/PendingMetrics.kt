package com.tracewayapp.traceway.metrics

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.internal.asLongExact
import com.tracewayapp.traceway.persistence.StateStore

private const val DEFAULT_KEY = "metricsPending"
private const val MAX_PAYLOADS = 3
private const val MAX_CHARS = 96 * 1024
private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

internal class PendingPayload(
    val id: String,
    val timeMs: Long,
    /** The OTLP request as protobuf, so a resend can restamp its resource. */
    val body: ByteArray,
)

/**
 * Metrics payloads whose upload failed or did not get an answer. Small and
 * short-lived on purpose, like the browser and Flutter SDKs': a device that
 * stays offline loses its oldest numbers, it never builds a backlog.
 *
 * Not thread-safe: [MetricsRecorder] calls it under its lock, with the
 * store it resolved before taking it.
 */
internal class PendingMetrics(
    private val now: () -> Long,
    scope: String = "",
) {
    val key: String = if (scope.isEmpty()) DEFAULT_KEY else "$DEFAULT_KEY:$scope"

    private fun read(store: StateStore): MutableList<Map<String, Any?>> {
        val raw = store.get(key).asJsonList() ?: return ArrayList()
        val oldest = now() - MAX_AGE_MS
        val out = ArrayList<Map<String, Any?>>()
        for (e in raw) {
            val entry = e.asJsonObject() ?: continue
            val t = entry["t"].asLongExact() ?: continue
            if (entry["id"] is String && t > oldest && entry["p"] is String) out.add(entry)
        }
        return out
    }

    private fun write(store: StateStore, list: MutableList<Map<String, Any?>>) {
        var size = Json.stringify(list).length
        while (list.isNotEmpty() && (list.size > MAX_PAYLOADS || size > MAX_CHARS)) {
            list.removeAt(0)
            size = Json.stringify(list).length
        }
        store.set(key, if (list.isEmpty()) null else list)
    }

    fun add(store: StateStore, id: String, body: ByteArray) {
        val list = read(store)
        list.removeAll { it["id"] == id }
        list.add(linkedMapOf("id" to id, "t" to now(), "p" to Base64.encode(body)))
        write(store, list)
    }

    fun remove(store: StateStore, id: String) {
        val list = read(store)
        if (list.any { it["id"] == id }) write(store, list.filterTo(ArrayList()) { it["id"] != id })
    }

    /**
     * Hands over everything except [skip] and forgets it: a payload gets one
     * more attempt, never a third.
     */
    fun take(store: StateStore, skip: Set<String>): List<PendingPayload> {
        val list = read(store)
        val taken = list.filter { it["id"] !in skip }
        if (taken.isNotEmpty()) write(store, list.filterTo(ArrayList()) { it["id"] in skip })
        return taken.mapNotNull { e ->
            val body = Base64.decode(e["p"] as String) ?: return@mapNotNull null
            PendingPayload(e["id"] as String, e["t"].asLongExact() ?: 0L, body)
        }
    }
}

/**
 * RFC 4648 Base64. `java.util.Base64` needs API 26 and `android.util.Base64`
 * is not there in JVM tests.
 */
internal object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest > 0) {
            val n = (bytes[i].toInt() and 0xff shl 16) or
                (if (rest == 2) bytes[i + 1].toInt() and 0xff shl 8 else 0)
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
            out.append(if (rest == 2) ALPHABET[n ushr 6 and 63] else '=').append('=')
        }
        return out.toString()
    }

    /** Null when [text] is not Base64. */
    fun decode(text: String): ByteArray? {
        var end = text.length
        while (end > 0 && text[end - 1] == '=') end--
        if (text.length % 4 != 0 || text.length - end > 2) return null
        val out = java.io.ByteArrayOutputStream(end * 3 / 4)
        var buffer = 0
        var bits = 0
        for (i in 0 until end) {
            val c = text[i].code
            val v = if (c < 128) DECODE[c] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer ushr bits and 0xff)
            }
        }
        return out.toByteArray()
    }
}
