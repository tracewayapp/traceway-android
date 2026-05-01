package com.tracewayapp.traceway.models

import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.mapToJson
import org.json.JSONObject

class MetricRecord(
    val name: String,
    val value: Double,
    val recordedAtMs: Long,
    val tags: Map<String, String>? = null,
) {
    fun toJson(): JSONObject {
        val map = mutableMapOf<String, Any?>(
            "name" to name,
            "value" to value,
            "recordedAt" to Iso8601.format(recordedAtMs),
        )
        if (tags != null) map["tags"] = tags
        return mapToJson(map)
    }
}
