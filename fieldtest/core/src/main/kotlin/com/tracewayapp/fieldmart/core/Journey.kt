package com.tracewayapp.fieldmart.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * `shared/journey.json` (02-APP-SPEC.md §5): every scenario as timed steps. Read leniently: fields this model does
 * not know stay in [Step.raw], so a newer journey still loads; [validate] says what it cannot play.
 */
class Journey(val version: Int, val viewportWidth: Int, val viewportHeight: Int, val scenarios: Map<String, Scenario>) {
    fun scenario(id: String): Scenario = scenarios[id]
        ?: scenarios.entries.firstOrNull { it.key.substringBefore('-') == id }?.value
        ?: throw IllegalArgumentException("no scenario $id in the journey (have ${scenarios.keys.joinToString()})")

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): Journey {
            val root = json.parseToJsonElement(text).jsonObject
            val viewport = root["viewport"] as? JsonObject
            val scenarios = LinkedHashMap<String, Scenario>()
            (root["scenarios"] as? JsonObject)?.forEach { (id, value) ->
                scenarios[id] = Scenario.parse(id, value.jsonObject)
            }
            return Journey(
                version = root.int("version") ?: 0,
                viewportWidth = viewport?.int("width") ?: 0,
                viewportHeight = viewport?.int("height") ?: 0,
                scenarios = scenarios,
            )
        }
    }
}

class Scenario(
    val id: String,
    /** `cold`, `warm` or `keep` (keep the SDK state from the run before). */
    val launch: String,
    val start: String,
    val durationMs: Long,
    val steps: List<Step>,
    val raw: JsonObject,
) {
    /** The variant an S7 part always runs in (`buffer`, `stream-img`), or null. */
    val variant: String? get() = raw.str("variant")

    /** Platforms the scenario exists on; empty means all. */
    val platforms: List<String> get() = raw.strings("platforms")

    /** Extra launch settings (`{"fmRemote": "1"}`). */
    val launchSettings: Map<String, String>
        get() = (raw["launchSettings"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() } ?: emptyMap()

    /** Steps for [platform]: those without `platforms`, and those that name it. */
    fun stepsFor(platform: String): List<Step> = steps.filter { it.platforms.isEmpty() || platform in it.platforms }

    val checkpoints: List<Step> get() = steps.filter { it.action == "checkpoint" }

    /** Whether the launch keeps app data (no `pm clear`). */
    val keepsState: Boolean get() = launch == "keep" || raw.bool("keepState") == true

    companion object {
        fun parse(id: String, o: JsonObject): Scenario = Scenario(
            id = id,
            launch = o.str("launch") ?: "cold",
            start = o.str("start") ?: "home",
            durationMs = o.long("durationMs") ?: 0,
            steps = (o["steps"] as? JsonArray)?.mapIndexed { i, e -> Step.parse(i, e.jsonObject) } ?: emptyList(),
            raw = o,
        )
    }
}

/** One step; [action] is the `do` field. */
class Step(val index: Int, val raw: JsonObject) {
    val t: Long = raw.long("t") ?: 0
    val action: String = raw.str("do") ?: ""
    val id: String? = raw.str("id")
    val name: String? = raw.str("name")
    val text: String? = raw.str("text")
    val value: String? = raw.str("value")
    val dir: String? = raw.str("dir")
    val mode: String? = raw.str("mode")
    val timeoutMs: Long = raw.long("timeoutMs") ?: 15_000
    val settleMs: Long = raw.long("settleMs") ?: 400
    val perCharMs: Long = raw.long("perCharMs") ?: 120
    val durationMs: Long? = raw.long("durationMs")
    val ms: Long? = raw.long("ms")
    val seconds: Long? = raw.long("seconds")
    val distance: Double = raw.double("distance") ?: 0.6
    val from: Pair<Double, Double>? = raw.point("from")
    val to: Pair<Double, Double>? = raw.point("to")
    val shows: List<String> = raw.strings("shows")
    val hides: List<String> = raw.strings("hides")
    val masked: List<String> = raw.strings("masked")
    /** Bring the element fully into view first (shared journey v1+). */
    val reveal: Boolean = raw.bool("reveal") == true
    /** The field `type` and `paste` write into. */
    val into: String? = raw.str("into")
    /** A `drag` that leaves the finger down until the next `release`. */
    val hold: Boolean = raw.bool("hold") == true
    /** A checkpoint taken while a held drag is in progress (`"drag"`). */
    val during: String? = raw.str("during")
    val overlay: String? = raw.str("overlay")
    val list: String? = raw.str("list")
    val screen: String? = raw.str("screen")
    val platforms: List<String> = raw.strings("platforms")

    /** A checkpoint at which `dumpsys meminfo` is taken (inside an idle span, the same moment in every variant). */
    val meminfo: Boolean = raw.bool("meminfo") == true || raw.bool("idle") == true

    /** How long the step itself takes on the device, for the overlap check and the dry run. */
    fun ownDurationMs(): Long = when (action) {
        "type" -> (text?.length ?: 0) * perCharMs
        "tap" -> if (reveal) 1150 else 250
        "fling" -> durationMs ?: DEFAULT_FLING_MS
        "drag" -> durationMs ?: 1000
        "longPress" -> ms ?: 800
        "pick" -> durationMs ?: 2500
        "idle" -> ms ?: 0
        "checkpoint" -> settleMs
        "background" -> 0
        else -> 0
    }

    override fun toString(): String = buildString {
        append("t=").append(t).append(' ').append(action)
        id?.let { append(' ').append(it) }
        name?.let { append(' ').append(it) }
        text?.let { append(" \"").append(if (it.length > 24) it.take(24) + "…" else it).append('"') }
        value?.let { append(" =").append(it) }
        dir?.let { append(' ').append(it) }
    }

    companion object {
        const val DEFAULT_FLING_MS = 120L
        fun parse(index: Int, o: JsonObject) = Step(index, o)
    }
}

/** The step names this driver plays (02-APP-SPEC.md §5.2). */
val KNOWN_ACTIONS = setOf(
    "waitFor", "tap", "longPress", "fling", "drag", "type", "paste", "key", "pick", "idle", "background",
    "foreground", "checkpoint", "mark", "fault", "kill", "relaunch", "release",
)

private val ID_PATTERN = Regex("^fm-[a-z]+(-[A-Za-z0-9]+)*$")

/** What in [scenario] this driver cannot play, or plays only approximately; empty when everything is fine. */
fun validate(scenario: Scenario): List<String> {
    val problems = ArrayList<String>()
    var last = -1L
    var busyUntil = 0L
    for (s in scenario.steps) {
        if (s.action !in KNOWN_ACTIONS) problems += "step ${s.index}: unknown action '${s.action}'"
        if (s.t < last) problems += "step ${s.index}: t=${s.t} before the previous step (t=$last)"
        last = s.t
        if (s.id != null && !ID_PATTERN.matches(s.id)) problems += "step ${s.index}: id '${s.id}' is not fm-<screen>-<element>"
        val input = s.action in setOf("tap", "longPress", "fling", "drag", "type", "paste", "key", "pick")
        if (input && s.t < busyUntil) problems += "step ${s.index}: starts at ${s.t} while the step before runs until $busyUntil"
        if (input || s.action == "checkpoint") busyUntil = maxOf(busyUntil, s.t + s.ownDurationMs())
        when (s.action) {
            "tap", "longPress", "fling", "drag", "waitFor", "pick" -> if (s.id == null) problems += "step ${s.index}: ${s.action} without id"
            "type", "paste" -> if (s.text == null) problems += "step ${s.index}: ${s.action} without text"
            "checkpoint" -> if (s.name == null) problems += "step ${s.index}: checkpoint without name"
            "fling" -> if (s.dir !in setOf("up", "down", "left", "right")) problems += "step ${s.index}: fling dir '${s.dir}'"
        }
    }
    if (scenario.durationMs > 0 && busyUntil > scenario.durationMs) problems += "steps run until $busyUntil, after durationMs ${scenario.durationMs}"
    return problems
}

/** The UiAutomator resource-id names an element id can have: Views `<pkg>:id/fm_home_grid`, Compose `fm-home-grid`. */
fun resourcePattern(id: String): String {
    val views = Regex.escape(id.replace('-', '_'))
    return "^(?:.*:id/$views|${Regex.escape(id)})$"
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
private fun JsonObject.int(k: String): Int? = long(k)?.toInt()
private fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun JsonObject.point(k: String): Pair<Double, Double>? = when (val v = this[k]) {
    null, JsonNull -> null
    is JsonArray -> if (v.size >= 2) Pair(v[0].num(), v[1].num()) else null
    is JsonObject -> Pair(v["x"]?.num() ?: 0.5, v["y"]?.num() ?: 0.5)
    is JsonPrimitive -> v.doubleOrNull?.let { Pair(it, 0.5) }
}

private fun JsonElement.num(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0

/** Pretty timeline of a scenario, for the dry run. */
fun describe(scenario: Scenario): String = buildString {
    append(scenario.id).append(": launch=").append(scenario.launch).append(" start=").append(scenario.start)
        .append(" durationMs=").append(scenario.durationMs).append(" steps=").append(scenario.steps.size)
        .append(" checkpoints=").append(scenario.checkpoints.size).append('\n')
    for (s in scenario.steps) append("  ").append(s.toString()).append('\n')
}

/** `java -cp … JourneyDryRun journey.json [scenario…]`: parses, validates and prints every scenario. */
object JourneyDryRun {
    @JvmStatic
    fun main(args: Array<String>) {
        val journey = Journey.parse(java.io.File(args[0]).readText())
        val ids = if (args.size > 1) args.drop(1) else journey.scenarios.keys.toList()
        var bad = 0
        for (id in ids) {
            val s = journey.scenario(id)
            print(describe(s))
            val problems = validate(s)
            problems.forEach { println("  ! $it") }
            if (problems.isNotEmpty()) bad++
        }
        println("${ids.size} scenarios, ${journey.scenarios.values.sumOf { it.checkpoints.size }} checkpoints, $bad with problems")
        if (bad > 0) System.exit(1)
    }
}
