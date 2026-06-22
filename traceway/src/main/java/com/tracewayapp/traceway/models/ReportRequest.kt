package com.tracewayapp.traceway.models

import org.json.JSONArray
import org.json.JSONObject

class ReportRequest(
    val collectionFrames: List<CollectionFrame>,
    val appVersion: String = "",
    val serverName: String = "",
    val proguardUuid: String? = null,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        val arr = JSONArray()
        collectionFrames.forEach { arr.put(it.toJson()) }
        obj.put("collectionFrames", arr)
        obj.put("appVersion", appVersion)
        obj.put("serverName", serverName)
        if (proguardUuid != null) obj.put("proguardUuid", proguardUuid)
        return obj
    }
}
