package com.tracewayapp.traceway.internal

import org.json.JSONArray
import org.json.JSONObject

internal fun mapToJson(map: Map<String, Any?>): JSONObject {
    val obj = JSONObject()
    for ((k, v) in map) {
        obj.put(k, toJsonValue(v))
    }
    return obj
}

internal fun listToJson(list: List<Any?>): JSONArray {
    val arr = JSONArray()
    for (v in list) {
        arr.put(toJsonValue(v))
    }
    return arr
}

@Suppress("UNCHECKED_CAST")
private fun toJsonValue(v: Any?): Any {
    return when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> v
        is Map<*, *> -> mapToJson(v as Map<String, Any?>)
        is List<*> -> listToJson(v as List<Any?>)
        is Array<*> -> listToJson(v.toList())
        else -> v
    }
}
