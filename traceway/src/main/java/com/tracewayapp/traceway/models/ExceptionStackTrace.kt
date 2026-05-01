package com.tracewayapp.traceway.models

import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.mapToJson
import org.json.JSONObject

class ExceptionStackTrace(
    var stackTrace: String,
    var recordedAtMs: Long,
    var traceId: String? = null,
    var isTask: Boolean = false,
    var attributes: Map<String, String>? = null,
    var isMessage: Boolean = false,
    var sessionRecordingId: String? = null,
    var distributedTraceId: String? = null,
    /** Transient file ID for disk persistence. Not serialized to the API. */
    var fileId: String? = null,
) {
    fun toJson(): JSONObject = mapToJson(
        mapOf(
            "traceId" to traceId,
            "isTask" to isTask,
            "stackTrace" to stackTrace,
            "recordedAt" to Iso8601.format(recordedAtMs),
            "attributes" to (attributes ?: emptyMap<String, String>()),
            "isMessage" to isMessage,
            "sessionRecordingId" to sessionRecordingId,
            "distributedTraceId" to distributedTraceId,
        )
    )

    companion object {
        fun fromJson(json: JSONObject): ExceptionStackTrace {
            val attrs = mutableMapOf<String, String>()
            json.optJSONObject("attributes")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    attrs[k] = obj.opt(k)?.toString() ?: ""
                }
            }
            return ExceptionStackTrace(
                stackTrace = json.optString("stackTrace", ""),
                recordedAtMs = Iso8601.parse(json.optString("recordedAt", "")),
                traceId = json.optString("traceId", "").ifEmpty { null },
                isTask = json.optBoolean("isTask", false),
                attributes = if (attrs.isEmpty()) null else attrs,
                isMessage = json.optBoolean("isMessage", false),
                sessionRecordingId = json.optString("sessionRecordingId", "").ifEmpty { null },
                distributedTraceId = json.optString("distributedTraceId", "").ifEmpty { null },
            )
        }
    }
}
