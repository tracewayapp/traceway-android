package com.tracewayapp.fieldmart.driver

import android.os.Bundle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One run, from the instrumentation arguments (`-e fmRun … -e fmScenario …`) or one entry of `fmPlan` (a JSON list
 * of such objects, inline or in the file `fmPlanFile`).
 */
class RunSpec(val values: Map<String, String>) {
    val run: String = values["fmRun"] ?: "manual-${System.currentTimeMillis()}"
    val scenario: String = values["fmScenario"] ?: error("fmScenario is required")
    val variant: String = values["fmVariant"] ?: "core"
    val evidence: Boolean = values["fmEvidence"] == "1"
    val pkg: String = values["fmPackage"] ?: if (variant.startsWith("off")) OFF_PACKAGE else SDK_PACKAGE
    val gateway: String = values["fmGatewayUrl"] ?: "http://127.0.0.1:18190"
    /** `startup`: the cold-start procedure of 03-MEASUREMENT.md §4 instead of a journey. */
    val mode: String = values["fmMode"] ?: "journey"
    val repeat: Int = values["fmRepeat"]?.toIntOrNull() ?: 10
    /** `pm clear` before the launch (default: unless the scenario keeps state). */
    val clear: Boolean? = values["fmClear"]?.let { it == "1" }
    /** How a journey `fling` reads `dir`: `finger` (the journey's rule since shared version 1) or `content`. */
    val flingDir: String = values["fmFlingDir"] ?: "finger"
    /**
     * After the standard ending, open the SDK build again this long after Home, under the run id with `-relaunch`:
     * with a short `fmSessionTimeoutMs` the run's session is over by then, and the SDK ends it at that start
     * (findings android-003/004). 0: no relaunch.
     */
    val relaunchAfterMs: Long = values["fmRelaunchAfterMs"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

    /** The launch settings the app stores (02-APP-SPEC.md §6). */
    fun launchExtras(scenarioSettings: Map<String, String> = emptyMap(), runId: String = run): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m.putAll(scenarioSettings)
        m["fmRun"] = runId
        m["fmVariant"] = variant
        m["fmScenario"] = scenario
        m["fmEvidence"] = if (evidence) "1" else "0"
        for (k in listOf("fmBaseUrl", "fmFixtures", "fmToken", "fmFps", "fmToolkit", "fmRemote", "fmTarget", "fmCampaign", "fmChart", "fmSessionTimeoutMs")) {
            values[k]?.let { m[k] = it }
        }
        return m
    }

    companion object {
        const val SDK_PACKAGE = "com.tracewayapp.fieldmart"
        const val OFF_PACKAGE = "com.tracewayapp.fieldmart.off"
        const val MAIN = "com.tracewayapp.fieldmart.MainActivity"
        const val CONFIG = "com.tracewayapp.fieldmart.ConfigActivity"

        fun from(args: Bundle, readFile: (String) -> String): List<RunSpec> {
            val base = LinkedHashMap<String, String>()
            for (k in args.keySet()) if (k.startsWith("fm")) args.getString(k)?.let { base[k] = it }
            val planText = base["fmPlan"] ?: base["fmPlanFile"]?.let(readFile)
            if (planText.isNullOrBlank()) return listOf(RunSpec(base))
            val plan = Json.parseToJsonElement(planText)
            val entries = (plan as? JsonArray) ?: ((plan as? JsonObject)?.get("runs") as? JsonArray) ?: JsonArray(emptyList())
            return entries.map { e ->
                val m = LinkedHashMap(base)
                m.remove("fmPlan")
                m.remove("fmPlanFile")
                (e as JsonObject).forEach { (k, v) -> (v as? JsonPrimitive)?.let { m[k] = it.content } }
                RunSpec(m)
            }
        }
    }
}
