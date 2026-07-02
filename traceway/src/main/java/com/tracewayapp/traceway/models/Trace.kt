package com.tracewayapp.traceway.models

import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.listToJson
import com.tracewayapp.traceway.internal.mapToJson
import org.json.JSONObject

class Trace(
    val id: String,
    val endpoint: String,
    val durationMs: Int,
    val recordedAtMs: Long,
    val statusCode: Int = 0,
    val bodySize: Int = 0,
    val clientIP: String = "",
    val attributes: Map<String, String>? = null,
    val spans: List<Span>? = null,
    val isTask: Boolean = false,
    val distributedTraceId: String? = null,
) {
    fun toJson(): JSONObject = mapToJson(
        mapOf(
            "id" to id,
            "endpoint" to endpoint,
            // The backend deserializes `duration` into a Go time.Duration,
            // so the wire value must be in nanoseconds.
            "duration" to durationMs * 1_000_000L,
            "recordedAt" to Iso8601.format(recordedAtMs),
            "statusCode" to statusCode,
            "bodySize" to bodySize,
            "clientIP" to clientIP,
            "attributes" to (attributes ?: emptyMap<String, String>()),
            "spans" to listToJson(spans?.map { it.toJson() } ?: emptyList()),
            "isTask" to isTask,
            "distributedTraceId" to (distributedTraceId ?: ""),
        )
    )
}
