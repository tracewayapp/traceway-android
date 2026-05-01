package com.tracewayapp.traceway.events

import com.tracewayapp.traceway.internal.Iso8601
import com.tracewayapp.traceway.internal.mapToJson
import org.json.JSONObject

/** A single entry in the breadcrumb timeline that ships with an exception. */
sealed class TracewayEvent(
    val timestampMs: Long = System.currentTimeMillis(),
) {
    abstract val type: String

    open fun toJson(): JSONObject = mapToJson(
        mapOf(
            "type" to type,
            "timestamp" to Iso8601.format(timestampMs),
        )
    )

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): TracewayEvent {
            return when (json.optString("type")) {
                "log" -> LogEvent.fromJson(json)
                "network" -> NetworkEvent.fromJson(json)
                "navigation" -> NavigationEvent.fromJson(json)
                "custom" -> CustomEvent.fromJson(json)
                else -> throw IllegalArgumentException("Unknown TracewayEvent type: ${json.optString("type")}")
            }
        }
    }
}

class LogEvent(
    val message: String,
    val level: String = "info",
    timestampMs: Long = System.currentTimeMillis(),
) : TracewayEvent(timestampMs) {
    override val type: String = "log"

    override fun toJson(): JSONObject {
        val json = super.toJson()
        json.put("level", level)
        json.put("message", message)
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): LogEvent = LogEvent(
            message = json.optString("message", ""),
            level = json.optString("level", "info"),
            timestampMs = Iso8601.parse(json.optString("timestamp", "")),
        )
    }
}

class NetworkEvent(
    val method: String,
    val url: String,
    val durationMs: Long,
    val statusCode: Int? = null,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val error: String? = null,
    timestampMs: Long = System.currentTimeMillis(),
) : TracewayEvent(timestampMs) {
    override val type: String = "network"

    override fun toJson(): JSONObject {
        val json = super.toJson()
        json.put("method", method)
        json.put("url", url)
        json.put("durationMs", durationMs)
        if (statusCode != null) json.put("statusCode", statusCode)
        if (requestBytes != null) json.put("requestBytes", requestBytes)
        if (responseBytes != null) json.put("responseBytes", responseBytes)
        if (error != null) json.put("error", error)
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): NetworkEvent = NetworkEvent(
            method = json.optString("method", ""),
            url = json.optString("url", ""),
            durationMs = json.optLong("durationMs", 0L),
            statusCode = if (json.has("statusCode")) json.optInt("statusCode") else null,
            requestBytes = if (json.has("requestBytes")) json.optLong("requestBytes") else null,
            responseBytes = if (json.has("responseBytes")) json.optLong("responseBytes") else null,
            error = if (json.has("error")) json.optString("error") else null,
            timestampMs = Iso8601.parse(json.optString("timestamp", "")),
        )
    }
}

class NavigationEvent(
    /** One of: push, pop, replace, remove. */
    val action: String,
    val from: String? = null,
    val to: String? = null,
    timestampMs: Long = System.currentTimeMillis(),
) : TracewayEvent(timestampMs) {
    override val type: String = "navigation"

    override fun toJson(): JSONObject {
        val json = super.toJson()
        json.put("action", action)
        if (from != null) json.put("from", from)
        if (to != null) json.put("to", to)
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): NavigationEvent = NavigationEvent(
            action = json.optString("action", ""),
            from = if (json.has("from")) json.optString("from") else null,
            to = if (json.has("to")) json.optString("to") else null,
            timestampMs = Iso8601.parse(json.optString("timestamp", "")),
        )
    }
}

class CustomEvent(
    val category: String,
    val name: String,
    val data: Map<String, Any?>? = null,
    timestampMs: Long = System.currentTimeMillis(),
) : TracewayEvent(timestampMs) {
    override val type: String = "custom"

    override fun toJson(): JSONObject {
        val json = super.toJson()
        json.put("category", category)
        json.put("name", name)
        if (data != null) json.put("data", mapToJson(data))
        return json
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): CustomEvent {
            val dataObj = json.optJSONObject("data")
            val data = if (dataObj != null) {
                val m = mutableMapOf<String, Any?>()
                val it = dataObj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = dataObj.opt(k)
                }
                m
            } else null
            return CustomEvent(
                category = json.optString("category", ""),
                name = json.optString("name", ""),
                data = data,
                timestampMs = Iso8601.parse(json.optString("timestamp", "")),
            )
        }
    }
}
