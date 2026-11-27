package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.TracewayImagePolicy
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.asFiniteDouble
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject

internal class SettingsError(message: String) : Exception(message)

internal typealias JsonObject = Map<String, Any?>

private val CONDITION_OPS = setOf(
    "eq", "neq", "in", "not_in", "contains", "icontains", "starts_with", "glob",
    "exists", "not_exists", "gt", "gte", "lt", "lte", "version",
)

private const val MAX_MATCHER_DEPTH = 8
private const val MAX_STEP_COUNT = 1000
private const val MAX_BUFFER_BYTES = 64.0 * 1024 * 1024
private const val MAX_BUFFER_AGE_SECONDS = 24.0 * 60 * 60

private fun rate(value: Any?): Double? = value.asFiniteDouble()?.coerceIn(0.0, 1.0)

private fun positive(value: Any?, max: Double): Double? {
    val d = value.asFiniteDouble() ?: return null
    if (d <= 0) return null
    return if (d > max) max else d
}

/** Parses the document the server sent. Throws [SettingsError] for anything this SDK cannot apply. */
internal fun parseSettingsDocument(body: String): JsonObject {
    val parsed = try {
        Json.parse(body)
    } catch (_: Exception) {
        throw SettingsError("settings document is not valid JSON")
    }
    val obj = parsed.asJsonObject() ?: throw SettingsError("settings document must be a JSON object")
    val version = obj["schemaVersion"]
    if (version != null && (version as? Number)?.toDouble() != 1.0) {
        throw SettingsError("unsupported settings schemaVersion $version")
    }
    return obj
}

private fun scalar(value: Any?): Boolean = value is String || value is Number || value is Boolean

private val EVENT_TYPES = setOf("click", "hover", "navigation", "log")
private val UNSUPPORTED_SELECTOR_CHARS = Regex("""[:,\\]""")

internal fun parseMatcher(value: Any?, depth: Int = 0): Matcher? {
    val obj = value.asJsonObject() ?: return null
    if (depth > MAX_MATCHER_DEPTH) return null
    val event = obj["event"].asJsonObject()
    if (event != null) {
        val type = event["type"]
        val text = event["value"]
        val mode = event["mode"]
        if (type !is String || type !in EVENT_TYPES || text !is String || text.isBlank() || text.length > 256) {
            return null
        }
        if (mode != null && mode != "exact" && mode != "contains") return null
        if ((type == "click" || type == "hover") && UNSUPPORTED_SELECTOR_CHARS.containsMatchIn(text)) return null
        return EventMatcher(type, text, mode as String?)
    }
    val all = obj["all"].asJsonList()
    val any = obj["any"].asJsonList()
    if (all != null || any != null) {
        val items = ArrayList<Matcher>()
        for (item in (all ?: any)!!) {
            items.add(parseMatcher(item, depth + 1) ?: return null)
        }
        return if (all != null) AllMatcher(items) else AnyMatcher(items)
    }
    if (obj.containsKey("not")) {
        val inner = parseMatcher(obj["not"], depth + 1) ?: return null
        return NotMatcher(inner)
    }
    val key = obj["key"]
    val op = obj["op"]
    if (key !is String || key.isEmpty()) return null
    if (op !is String || op !in CONDITION_OPS) return null
    val raw = obj["value"]
    if (raw != null && !scalar(raw)) return null
    var values: List<Any>? = null
    val rawValues = obj["values"]
    if (rawValues != null) {
        val list = rawValues.asJsonList() ?: return null
        if (!list.all { scalar(it) }) return null
        @Suppress("UNCHECKED_CAST")
        values = list as List<Any>
    }
    return Condition(key, op, raw, values)
}

private fun parseSignalSelector(value: Any?): List<String>? {
    val list = value.asJsonList() ?: return null
    return list.filterIsInstance<String>().filter { it == "*" || it in SIGNAL_NAMES }
}

private fun parseAction(value: Any?): TriggerAction? {
    val obj = value.asJsonObject() ?: return null
    return when (obj["type"]) {
        "flush" -> {
            val lookback = positive(obj["lookbackSeconds"], MAX_BUFFER_AGE_SECONDS) ?: return null
            TriggerAction(
                type = "flush",
                lookbackSeconds = lookback,
                signals = parseSignalSelector(obj["signals"]),
                replay = obj["replay"] == true,
            )
        }
        "stream" -> {
            val duration = positive(obj["durationSeconds"], MAX_BUFFER_AGE_SECONDS) ?: return null
            TriggerAction(
                type = "stream",
                durationSeconds = duration,
                signals = parseSignalSelector(obj["signals"]),
                replay = obj["replay"] == true,
            )
        }
        "startReplay" -> {
            val duration = positive(obj["durationSeconds"], MAX_BUFFER_AGE_SECONDS) ?: return null
            TriggerAction(type = "startReplay", durationSeconds = duration)
        }
        "capture" -> {
            val duration = positive(obj["durationSeconds"], MAX_BUFFER_AGE_SECONDS) ?: return null
            TriggerAction(
                type = "capture",
                durationSeconds = duration,
                lookbackSeconds = positive(obj["lookbackSeconds"], MAX_BUFFER_AGE_SECONDS),
                signals = parseSignalSelector(obj["signals"]),
                replay = obj["replay"] == true,
            )
        }
        "emit" -> {
            val eventName = obj["eventName"]
            if (eventName !is String || eventName.isEmpty()) return null
            val attributes = LinkedHashMap<String, String>()
            obj["attributes"].asJsonObject()?.forEach { (k, v) -> if (v is String) attributes[k] = v }
            TriggerAction(type = "emit", eventName = eventName, attributes = attributes)
        }
        else -> null
    }
}

private fun parseSteps(value: Any?): List<TriggerStep>? {
    val list = value.asJsonList() ?: return null
    if (list.isEmpty()) return null
    val steps = ArrayList<TriggerStep>()
    for (raw in list) {
        val obj = raw.asJsonObject() ?: return null
        val match = parseMatcher(obj["match"]) ?: return null
        val within = obj["withinSeconds"].asFiniteDouble()
        var count: Int? = null
        var period: Double? = null
        val rawCount = obj["count"].asFiniteDouble()
        if (rawCount != null && rawCount == Math.rint(rawCount) && rawCount > 1) {
            count = if (rawCount > MAX_STEP_COUNT) MAX_STEP_COUNT else rawCount.toInt()
            val rawPeriod = obj["periodSeconds"].asFiniteDouble()
            period = if (rawPeriod != null && rawPeriod > 0) minOf(rawPeriod, MAX_BUFFER_AGE_SECONDS) else null
        }
        steps.add(
            TriggerStep(
                match = match,
                withinSeconds = if (within != null && within > 0) within else null,
                count = count,
                periodSeconds = period,
            ),
        )
    }
    return steps
}

private fun parseActions(value: Any?): List<TriggerAction>? {
    val list = value.asJsonList() ?: return null
    if (list.isEmpty()) return null
    val actions = ArrayList<TriggerAction>()
    for (raw in list) actions.add(parseAction(raw) ?: return null)
    return actions
}

private fun mergeTrigger(base: Trigger?, raw: JsonObject): Trigger? {
    val steps = if (raw.containsKey("steps")) parseSteps(raw["steps"]) else base?.steps
    val actions = if (raw.containsKey("actions")) parseActions(raw["actions"]) else base?.actions
    if (steps == null || actions == null) return null
    val cooldown = raw["cooldownSeconds"].asFiniteDouble()
    val maxPerSession = raw["maxPerSession"].asFiniteDouble()
    return (base ?: Trigger(id = raw["id"] as String, steps = steps, actions = actions)).copyWith(
        steps = steps,
        actions = actions,
        name = raw["name"] as? String,
        enabled = raw["enabled"] as? Boolean,
        sampleRate = rate(raw["sampleRate"]),
        cooldownSeconds = if (cooldown != null && cooldown >= 0) cooldown else null,
        maxPerSession = if (maxPerSession != null && maxPerSession >= 0) maxPerSession.toInt() else null,
    )
}

private fun parseCapture(value: Any?): Capture? {
    val obj = value.asJsonObject() ?: return null
    val id = obj["id"]
    val until = obj["until"]
    if (id !is String || until !is String) return null
    val untilMs = Rfc3339.parse(until) ?: return null
    val rawLookback = obj["lookbackSeconds"]
    return Capture(
        id = id,
        untilMs = untilMs,
        replay = obj["replay"] == true,
        lookbackSeconds = if (rawLookback.asFiniteDouble() == 0.0) 0.0 else positive(rawLookback, MAX_BUFFER_AGE_SECONDS),
        signals = parseSignalSelector(obj["signals"]),
    )
}

/**
 * Lays a remote overlay over [base]. Fields this SDK does not know are
 * ignored so a newer server can add settings; malformed values of known
 * fields are skipped one by one instead of rejecting the whole document.
 */
internal fun applyOverlay(base: Settings, overlay: JsonObject): Settings {
    val out = base.copy()
    (overlay["enabled"] as? Boolean)?.let { out.enabled = it }

    overlay["signals"].asJsonObject()?.let { signals ->
        for (signal in SIGNAL_NAMES) {
            val raw = signals[signal].asJsonObject() ?: continue
            val target = out.signal(signal)
            Mode.from(raw["mode"])?.let { target.mode = it }
            rate(raw["sampleRate"])?.let { target.sampleRate = it }
            val minSeverity = raw["minSeverity"]
            if (minSeverity is String && minSeverity in SEVERITIES) target.minSeverity = minSeverity
        }
    }

    overlay["sessions"].asJsonObject()?.let { sessions ->
        (sessions["enabled"] as? Boolean)?.let { out.sessionsEnabled = it }
        rate(sessions["baselineSampleRate"])?.let { out.baselineSampleRate = it }
    }

    overlay["replay"].asJsonObject()?.let { replay ->
        Mode.from(replay["mode"])?.let { out.replayMode = it }
        rate(replay["sessionSampleRate"])?.let { out.replaySessionSampleRate = it }
        (replay["maskAllInputs"] as? Boolean)?.let { out.replayMaskAllInputs = it }
        TracewayImagePolicy.from(replay["images"])?.let { out.replayImages = it }
        // `captureMode` (video or wireframe) is the Flutter SDK's; Android always records a wireframe.
    }

    overlay["metrics"].asJsonObject()?.let { metrics ->
        (metrics["enabled"] as? Boolean)?.let { out.metrics.enabled = it }
        rate(metrics["sampleRate"])?.let { out.metrics.sampleRate = it }
        positive(metrics["flushSeconds"], MAX_METRICS_FLUSH_SECONDS)?.let {
            out.metrics.flushSeconds = maxOf(it, MIN_METRICS_FLUSH_SECONDS)
        }
    }

    overlay["buffer"].asJsonObject()?.let { buffer ->
        positive(buffer["maxBytes"], MAX_BUFFER_BYTES)?.let { out.bufferMaxBytes = it.toLong() }
        positive(buffer["maxAgeSeconds"], MAX_BUFFER_AGE_SECONDS)?.let { out.bufferMaxAgeSeconds = it }
        (buffer["persist"] as? Boolean)?.let { out.persist = it }
    }

    overlay["triggers"].asJsonList()?.let { triggers ->
        for (item in triggers) {
            val raw = item.asJsonObject() ?: continue
            val id = raw["id"]
            if (id !is String || id.isEmpty()) continue
            val index = out.triggers.indexOfFirst { it.id == id }
            val merged = mergeTrigger(if (index >= 0) out.triggers[index] else null, raw) ?: continue
            if (index >= 0) out.triggers[index] = merged else out.triggers.add(merged)
        }
    }

    overlay["captures"].asJsonList()?.let { captures ->
        out.captures = captures.mapNotNull { parseCapture(it) }.toMutableList()
    }

    return out
}

/** Lowers the app's ceilings to the `limits` a remote document carries: what the organization's plan accepts. */
internal fun applyLimits(ceilings: Ceilings, overlay: JsonObject?): Ceilings {
    val limits = overlay?.get("limits").asJsonObject() ?: return ceilings
    val signals = LinkedHashMap(ceilings.signals)
    limits["signals"].asJsonObject()?.let { limitSignals ->
        for (signal in SIGNAL_NAMES) {
            val mode = Mode.from(limitSignals[signal]) ?: continue
            signals[signal] = lowerMode(signals.getValue(signal), mode)
        }
    }
    val replay = Mode.from(limits["replay"])
    return Ceilings(
        enabled = ceilings.enabled,
        signals = signals,
        replay = if (replay == null) ceilings.replay else lowerMode(ceilings.replay, replay),
        sessions = ceilings.sessions,
        metrics = ceilings.metrics && limits["metrics"] != "off",
        replayImages = ceilings.replayImages,
    )
}

/** Holds every mode at or below what init allowed in this app. */
internal fun clampToCeilings(settings: Settings, ceilings: Ceilings): Settings {
    val out = settings.copy()
    out.enabled = out.enabled && ceilings.enabled
    for (signal in SIGNAL_NAMES) {
        val target = out.signal(signal)
        target.mode = lowerMode(target.mode, ceilings.signals.getValue(signal))
    }
    out.replayMode = lowerMode(out.replayMode, ceilings.replay)
    // No replay, no pictures in it: the app's screen capture or the plan's limits turned it off.
    out.replayImages = if (ceilings.replay == Mode.OFF) {
        TracewayImagePolicy.OFF
    } else {
        TracewayImagePolicy.lower(out.replayImages, ceilings.replayImages)
    }
    out.sessionsEnabled = out.sessionsEnabled && ceilings.sessions
    out.metrics.enabled = out.metrics.enabled && ceilings.metrics
    return out
}
