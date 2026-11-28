package com.tracewayapp.traceway.core

import java.util.Random

internal class ObservedEvent(
    val timeMs: Long,
    val lookup: FieldLookup,
    /**
     * Set for the tap and hover events the app feeds in directly (`click` or
     * `hover`). They answer almost no field, so only a step that names this
     * event type may match them: a negated condition would otherwise match
     * every tap.
     */
    val sequence: String? = null,
)

internal class FiredTrigger(
    val trigger: Trigger,
    /** The event that completed the trigger, e.g. the exception a clip belongs to. */
    val cause: ObservedEvent,
)

/** Fires and the last fire time of one trigger in the current session. */
internal data class TriggerLimit(val fires: Int, val lastFireMs: Long)

/** A completed prefix waiting for its next step: when it completed, and the hit it started counting from. */
private class Anchor(val at: Long, var from: Int)

private class StepState {
    /** Unused on the first step, which is always open. Oldest first. */
    var anchors: MutableList<Anchor> = ArrayList()

    /** Times of the records counted towards this step, when it needs more than one. */
    var hits: MutableList<Long> = ArrayList()

    /** Hits dropped from the front of [hits], so an anchor's `from` stays valid. */
    var base: Int = 0
}

private class TriggerState(
    val signature: String,
    var steps: List<StepState>,
    /** Per step, the event types (`click`, `hover`, ...) its matcher names. */
    val kinds: List<Set<String>>,
    var fires: Int = 0,
    var lastFireMs: Long = 0,
)

private const val MAX_ANCHORS = 8
private const val MAX_HITS = 1000
private const val MAX_RESTORE_AGE_MS = 86_400_000L
private const val MAX_RESTORED_TRIGGERS = 20

/** Seconds to milliseconds, rounding half away from zero as Dart's `round()` does. */
private fun secondsToMs(seconds: Double): Long {
    val ms = seconds * 1000
    return if (ms < 0) -Math.round(-ms) else Math.round(ms)
}

/** A JSON integer (Dart's `is int`): integral boxed types only, not doubles. */
private fun asInt(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    else -> null
}

private fun freshSteps(count: Int): List<StepState> = List(count) { StepState() }

/**
 * Runs the server-defined triggers against every event on the device. A
 * trigger is a sequence of steps. Every way the sequence could be under way
 * is followed at once: each step remembers when the steps before it last
 * completed, so an event that could also start the sequence over does not
 * discard the progress already made. A step that arrives later than its
 * `withinSeconds` is dropped, and the last step fires the trigger's actions.
 * Progress and fire counts are per session.
 *
 * The same algorithm as the browser and Flutter SDKs, so a rule behaves the
 * same on all of them. Not thread-safe: the client calls it under its lock.
 */
internal class TriggerEngine(
    random: (() -> Double)? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val random: () -> Double = random ?: Random().let { r -> { r.nextDouble() } }
    private var triggerList: List<Trigger> = emptyList()
    private var states: MutableMap<String, TriggerState> = LinkedHashMap()

    /** Fires and the last fire time per trigger id in the current session. */
    var sessionLimits: MutableMap<String, TriggerLimit> = LinkedHashMap()

    /** Changes whenever progress does, so the caller can skip saving it. */
    var progressVersion: Int = 0
        private set

    /** Why a trigger that completed did not fire on the last [observe] call. */
    val skipped: MutableMap<String, String> = LinkedHashMap()

    val triggers: List<Trigger> get() = triggerList

    /**
     * Returns the ids of the triggers that start without progress: new ones,
     * and ones whose steps or actions changed. Every other trigger keeps the
     * progress it had.
     */
    fun setTriggers(triggers: List<Trigger>): Set<String> {
        triggerList = triggers.filter { it.enabled != false && it.steps.isNotEmpty() && it.actions.isNotEmpty() }
        val next = LinkedHashMap<String, TriggerState>()
        val created = LinkedHashSet<String>()
        for (trigger in triggerList) {
            val signature = trigger.signature
            val prev = states[trigger.id]
            val limits = sessionLimits[trigger.id]
            val kept = prev != null && prev.signature == signature
            if (!kept) created.add(trigger.id)
            next[trigger.id] = if (kept) {
                prev!!
            } else {
                TriggerState(
                    signature,
                    freshSteps(trigger.steps.size),
                    trigger.steps.map { eventKinds(it.match) },
                    fires = prev?.fires ?: limits?.fires ?: 0,
                    lastFireMs = prev?.lastFireMs ?: limits?.lastFireMs ?: 0,
                )
            }
        }
        states = next
        progressVersion++
        return created
    }

    /** The progress of every trigger under way, as JSON values, to continue it after an app restart within the session. */
    fun snapshotProgress(): List<Any> {
        val out = ArrayList<Any>()
        for ((id, state) in states) {
            if (state.steps.none { it.anchors.isNotEmpty() || it.hits.isNotEmpty() }) continue
            out.add(
                linkedMapOf(
                    "id" to id,
                    "v" to 2L,
                    "signature" to state.signature,
                    "steps" to state.steps.map { step ->
                        linkedMapOf(
                            "anchors" to step.anchors.map { a -> listOf(a.at, maxOf(0, a.from - step.base).toLong()) },
                            "hits" to ArrayList<Long>(step.hits),
                        )
                    },
                ),
            )
        }
        return out
    }

    /** Continues progress saved by [snapshotProgress]. [only] limits the restore to those trigger ids. */
    fun restoreProgress(saved: Any?, nowMs: Long? = null, only: Set<String>? = null) {
        if (saved !is List<*>) return
        val t = nowMs ?: now()
        for (item in saved.take(MAX_RESTORED_TRIGGERS)) {
            if (item !is Map<*, *> || (item["v"] as? Number)?.toDouble() != 2.0) continue
            val id = item["id"] as? String ?: continue
            if (only != null && id !in only) continue
            val state = states[id] ?: continue
            val trigger = triggerList.lastOrNull { it.id == id } ?: continue
            val steps = item["steps"]
            if (state.signature != item["signature"] || steps !is List<*> || steps.size != trigger.steps.size) {
                continue
            }
            state.steps = trigger.steps.indices.map { k -> restoreStep(trigger.steps[k], k, steps[k], t) }
            progressVersion++
        }
    }

    private fun restoreStep(step: TriggerStep, k: Int, from: Any?, now: Long): StepState {
        val restored = StepState()
        if (from !is Map<*, *>) return restored
        val rawHits = from["hits"]
        val needed = step.count ?: 1
        if (needed > 1) {
            val period = step.periodSeconds
            val oldest = if (period != null) now - secondsToMs(period) else now - MAX_RESTORE_AGE_MS
            val hits = ArrayList<Long>()
            if (rawHits is List<*>) {
                for (v in rawHits) {
                    if (v !is Number) continue
                    val d = v.toDouble()
                    if (d.isNaN() || d.isInfinite() || d > now || d < oldest) continue
                    hits.add(v.toLong())
                }
            }
            restored.hits = if (hits.size > MAX_HITS) ArrayList(hits.subList(hits.size - MAX_HITS, hits.size)) else hits
        }
        val rawAnchors = from["anchors"]
        if (k == 0 || rawAnchors !is List<*>) {
            if (k > 0) restored.hits = ArrayList()
            return restored
        }
        val within = step.withinSeconds
        val maxAge = if (within != null) secondsToMs(within) else MAX_RESTORE_AGE_MS
        val dropped = if (rawHits is List<*> && needed > 1) rawHits.size - restored.hits.size else 0
        for (anchor in rawAnchors.take(MAX_ANCHORS)) {
            if (anchor !is List<*> || anchor.size < 2) continue
            val at = anchor[0] as? Number ?: continue
            val atMs = at.toDouble()
            val start = asInt(anchor[1]) ?: continue
            if (atMs.isNaN() || atMs.isInfinite() || atMs > now || now - atMs > maxAge || start < 0) continue
            val fromHit = minOf(restored.hits.size.toLong(), maxOf(0L, start - dropped)).toInt()
            restored.anchors.add(Anchor(at.toLong(), fromHit))
        }
        if (restored.anchors.isEmpty()) restored.hits = ArrayList()
        return restored
    }

    fun resetSession() {
        sessionLimits = LinkedHashMap()
        for (state in states.values) {
            state.steps = freshSteps(state.steps.size)
            state.fires = 0
            state.lastFireMs = 0
        }
        progressVersion++
    }

    /** The fire counts of this session as JSON values, to keep `maxPerSession` and cooldowns across a restart. */
    fun snapshotSessionLimits(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((id, limit) in sessionLimits) {
            out[id] = linkedMapOf("fires" to limit.fires.toLong(), "lastFireMs" to limit.lastFireMs)
        }
        return out
    }

    fun restoreSessionLimits(saved: Any?) {
        if (saved !is Map<*, *>) return
        for ((id, value) in saved) {
            if (id !is String || value !is Map<*, *>) continue
            val fires = asInt(value["fires"]) ?: continue
            val last = asInt(value["lastFireMs"]) ?: continue
            if (fires < 0 || fires > Int.MAX_VALUE) continue
            val limit = TriggerLimit(fires.toInt(), last)
            sessionLimits[id] = limit
            states[id]?.let { state ->
                state.fires = limit.fires
                state.lastFireMs = limit.lastFireMs
            }
        }
    }

    fun observe(event: ObservedEvent): List<FiredTrigger> {
        skipped.clear()
        val fired = ArrayList<FiredTrigger>()
        for (trigger in triggerList) {
            val state = states[trigger.id] ?: continue
            if (!advance(trigger, state, event)) continue
            if (!mayFire(trigger, state, event.timeMs)) continue
            state.fires++
            state.lastFireMs = event.timeMs
            sessionLimits[trigger.id] = TriggerLimit(state.fires, state.lastFireMs)
            fired.add(FiredTrigger(trigger, event))
        }
        return fired
    }

    /**
     * Feeds one record to a trigger; true when it completed the last step.
     * Later steps are tried first, so the progress a record makes on one step
     * is not also counted towards the next one.
     */
    private fun advance(trigger: Trigger, state: TriggerState, event: ObservedEvent): Boolean {
        val last = trigger.steps.size - 1
        val now = event.timeMs
        var completed = false
        for (k in last downTo 1) {
            if (completed) break
            val step = trigger.steps[k]
            val at = state.steps[k]
            if (at.anchors.isEmpty()) continue
            val within = step.withinSeconds
            if (within != null && within > 0) {
                val oldest = now - secondsToMs(within)
                if (at.anchors.first().at < oldest) {
                    at.anchors = at.anchors.filterTo(ArrayList()) { it.at >= oldest }
                    if (at.anchors.isEmpty()) at.hits = ArrayList()
                    progressVersion++
                    if (at.anchors.isEmpty()) continue
                }
            }
            if (!stepMatches(step, state.kinds[k], event)) continue
            progressVersion++
            val needed = step.count ?: 1
            if (needed > 1) {
                countHit(at, step, now)
                val counted = at.base + at.hits.size - maxOf(at.anchors.first().from, at.base)
                if (counted < needed) continue
                at.anchors.removeAt(0)
                if (at.anchors.isEmpty()) at.hits = ArrayList()
            } else {
                at.anchors = ArrayList()
            }
            if (k == last) {
                completed = true
            } else {
                open(state.steps[k + 1], trigger.steps[k + 1], now)
            }
        }
        if (!completed) {
            val step = trigger.steps[0]
            if (!stepMatches(step, state.kinds[0], event)) return false
            val first = state.steps[0]
            val needed = step.count ?: 1
            if (needed > 1) {
                progressVersion++
                countHit(first, step, now)
                if (first.hits.size < needed) return false
                first.hits = ArrayList()
                first.base = 0
            }
            if (last == 0) {
                completed = true
            } else {
                progressVersion++
                open(state.steps[1], trigger.steps[1], now)
            }
        }
        if (!completed) return false
        if (last > 0) {
            state.steps = freshSteps(state.steps.size)
            progressVersion++
        }
        return true
    }

    private fun mayFire(trigger: Trigger, state: TriggerState, nowMs: Long): Boolean {
        val maxPerSession = trigger.maxPerSession
        if (maxPerSession != null && state.fires >= maxPerSession) {
            skipped[trigger.id] = "session_clip_limit"
            return false
        }
        val cooldown = trigger.cooldownSeconds
        if (cooldown != null && cooldown > 0 && state.fires > 0 && (nowMs - state.lastFireMs) < cooldown * 1000) {
            skipped[trigger.id] = "cooldown"
            return false
        }
        val sampleRate = trigger.sampleRate ?: 1.0
        val accepted = sampleRate >= 1 || random() < sampleRate
        if (!accepted) skipped[trigger.id] = "sampled_out"
        return accepted
    }
}

private fun countHit(at: StepState, step: TriggerStep, now: Long) {
    at.hits.add(now)
    var drop = maxOf(0, at.hits.size - MAX_HITS)
    val period = step.periodSeconds
    if (period != null && period > 0) {
        val cutoff = now - secondsToMs(period)
        while (drop < at.hits.size && at.hits[drop] < cutoff) drop++
    }
    if (drop > 0) {
        at.hits.subList(0, drop).clear()
        at.base += drop
    }
}

/** Records that the steps before this one just completed. */
private fun open(at: StepState, step: TriggerStep, now: Long) {
    if ((step.count ?: 1) <= 1) {
        at.anchors = arrayListOf(Anchor(now, 0))
        return
    }
    val anchor = Anchor(now, at.base + at.hits.size)
    if (at.anchors.size >= MAX_ANCHORS) {
        at.anchors[MAX_ANCHORS - 1] = anchor
    } else {
        at.anchors.add(anchor)
    }
}

private fun stepMatches(step: TriggerStep, kinds: Set<String>, event: ObservedEvent): Boolean {
    val sequence = event.sequence
    if (sequence != null && sequence !in kinds) return false
    return matches(step.match, event.lookup)
}

/** The event types (`click`, `hover`, `navigation`, `log`) a matcher names. */
internal fun eventKinds(matcher: Matcher, into: MutableSet<String> = LinkedHashSet()): MutableSet<String> {
    when (matcher) {
        is EventMatcher -> into.add(matcher.type)
        is AllMatcher -> for (item in matcher.items) eventKinds(item, into)
        is AnyMatcher -> for (item in matcher.items) eventKinds(item, into)
        is NotMatcher -> eventKinds(matcher.inner, into)
        is Condition -> Unit
    }
    return into
}
