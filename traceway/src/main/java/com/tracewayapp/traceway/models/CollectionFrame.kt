package com.tracewayapp.traceway.models

import org.json.JSONArray
import org.json.JSONObject

class CollectionFrame(
    val stackTraces: List<ExceptionStackTrace>,
    val metrics: List<MetricRecord> = emptyList(),
    val traces: List<Trace> = emptyList(),
    val sessionRecordings: List<SessionRecordingPayload>? = null,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("stackTraces", JSONArray().also { arr -> stackTraces.forEach { arr.put(it.toJson()) } })
        obj.put("metrics", JSONArray().also { arr -> metrics.forEach { arr.put(it.toJson()) } })
        obj.put("traces", JSONArray().also { arr -> traces.forEach { arr.put(it.toJson()) } })
        if (sessionRecordings != null) {
            val arr = JSONArray()
            sessionRecordings.forEach { arr.put(it.toJson()) }
            obj.put("sessionRecordings", arr)
        }
        return obj
    }
}
