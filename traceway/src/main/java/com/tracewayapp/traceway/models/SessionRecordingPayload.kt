package com.tracewayapp.traceway.models

import com.tracewayapp.traceway.events.LogEvent
import com.tracewayapp.traceway.events.TracewayEvent
import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.listToJson
import org.json.JSONArray
import org.json.JSONObject

class SessionRecordingPayload(
    val exceptionId: String,
    val events: List<JSONObject> = emptyList(),
    val logs: List<LogEvent> = emptyList(),
    val actions: List<TracewayEvent> = emptyList(),
    /**
     * Wall-clock timestamp of the first event in this recording. Combined with
     * [endedAtMs] this lets the backend align logs and actions onto the
     * timeline: `offsetMs = event.timestamp - startedAtMs`.
     */
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("exceptionId", exceptionId)
        obj.put("events", listToJson(events))
        if (startedAtMs != null) obj.put("startedAt", Iso8601.format(startedAtMs))
        if (endedAtMs != null) obj.put("endedAt", Iso8601.format(endedAtMs))
        if (logs.isNotEmpty()) {
            val arr = JSONArray()
            for (l in logs) arr.put(l.toJson())
            obj.put("logs", arr)
        }
        if (actions.isNotEmpty()) {
            val arr = JSONArray()
            for (a in actions) arr.put(a.toJson())
            obj.put("actions", arr)
        }
        return obj
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): SessionRecordingPayload {
            val rawEvents = json.optJSONArray("events")
            val events = mutableListOf<JSONObject>()
            if (rawEvents != null) {
                for (i in 0 until rawEvents.length()) {
                    rawEvents.optJSONObject(i)?.let { events.add(it) }
                }
            }

            val rawLogs = json.optJSONArray("logs")
            val logs = mutableListOf<LogEvent>()
            if (rawLogs != null) {
                for (i in 0 until rawLogs.length()) {
                    rawLogs.optJSONObject(i)?.let { logs.add(LogEvent.fromJson(it)) }
                }
            }

            val rawActions = json.optJSONArray("actions")
            val actions = mutableListOf<TracewayEvent>()
            if (rawActions != null) {
                for (i in 0 until rawActions.length()) {
                    rawActions.optJSONObject(i)?.let {
                        try {
                            actions.add(TracewayEvent.fromJson(it))
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            return SessionRecordingPayload(
                exceptionId = json.optString("exceptionId", ""),
                events = events,
                logs = logs,
                actions = actions,
                startedAtMs = if (json.has("startedAt")) Iso8601.parse(json.optString("startedAt")) else null,
                endedAtMs = if (json.has("endedAt")) Iso8601.parse(json.optString("endedAt")) else null,
            )
        }
    }
}
