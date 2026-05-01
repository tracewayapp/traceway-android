package com.tracewayapp.traceway.models

import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.mapToJson
import org.json.JSONObject

class Span(
    val id: String,
    val name: String,
    val startTimeMs: Long,
    val durationMs: Int,
) {
    fun toJson(): JSONObject = mapToJson(
        mapOf(
            "id" to id,
            "name" to name,
            "startTime" to Iso8601.format(startTimeMs),
            "duration" to durationMs,
        )
    )
}
