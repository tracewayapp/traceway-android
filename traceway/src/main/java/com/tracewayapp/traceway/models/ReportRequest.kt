package com.tracewayapp.traceway.models

import org.json.JSONArray
import org.json.JSONObject

class ReportRequest(
    val collectionFrames: List<CollectionFrame>,
    val appVersion: String = "",
    val serverName: String = "",
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        val arr = JSONArray()
        collectionFrames.forEach { arr.put(it.toJson()) }
        obj.put("collectionFrames", arr)
        obj.put("appVersion", appVersion)
        obj.put("serverName", serverName)
        return obj
    }
}
