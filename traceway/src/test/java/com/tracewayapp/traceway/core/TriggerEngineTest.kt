package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.asJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Builds a trigger the way the SDK does, from a settings overlay. */
internal fun parseTrigger(json: String): Trigger {
    val raw = Json.parse(json).asJsonObject()!!
    return applyOverlay(defaultSettings(), mapOf("triggers" to listOf(raw))).triggers.first { it.id == raw["id"] }
}

internal fun observed(timeMs: Long, fields: Map<String, Any?>, sequence: String? = null) =
    ObservedEvent(timeMs = timeMs, lookup = { fields[it] }, sequence = sequence)

internal fun named(name: String) = """{"key":"event.name","op":"eq","value":"$name"}"""

class TriggerEngineTest {

    private fun event(timeMs: Long, name: String) = observed(timeMs, mapOf("event.name" to name))

    @Test
    fun `default trigger fires on exceptions with cooldown and cap`() {
        val engine = TriggerEngine()
        engine.setTriggers(listOf(defaultExceptionTrigger().copyWith(maxPerSession = 2)))
        assertTrue(engine.observe(event(0, "other")).isEmpty())
        assertEquals(1, engine.observe(event(1000, "exception")).size)
        assertTrue(engine.skipped.isEmpty())
        assertTrue(engine.observe(event(2000, "exception")).isEmpty())
        assertEquals("cooldown", engine.skipped[DEFAULT_EXCEPTION_TRIGGER_ID])
        assertEquals(1, engine.observe(event(7000, "exception")).size)
        assertTrue(engine.observe(event(20000, "exception")).isEmpty())
        assertEquals("session_clip_limit", engine.skipped[DEFAULT_EXCEPTION_TRIGGER_ID])
        // Skips describe the last observe call only.
        engine.observe(event(20001, "other"))
        assertTrue(engine.skipped.isEmpty())
        engine.resetSession()
        assertEquals(1, engine.observe(event(30000, "exception")).size)
    }

    @Test
    fun `a fired trigger carries the event that completed it`() {
        val engine = TriggerEngine()
        engine.setTriggers(listOf(defaultExceptionTrigger()))
        val cause = observed(5, mapOf("event.name" to "exception", "attributes.traceway.exception.id" to "e1"))
        val fired = engine.observe(cause).single()
        assertEquals(DEFAULT_EXCEPTION_TRIGGER_ID, fired.trigger.id)
        assertTrue(fired.cause === cause)
        assertEquals("e1", fired.cause.lookup("attributes.traceway.exception.id"))
    }

    @Test
    fun `cooldown is measured from the last fire, not the last attempt`() {
        val engine = TriggerEngine()
        engine.setTriggers(listOf(defaultExceptionTrigger()))
        assertEquals(1, engine.observe(event(0, "exception")).size)
        assertTrue(engine.observe(event(4000, "exception")).isEmpty())
        assertEquals(1, engine.observe(event(5000, "exception")).size)
    }

    @Test
    fun `multi-step trigger honours withinSeconds`() {
        val step = Condition("event.name", "eq", "fail")
        val engine = TriggerEngine()
        engine.setTriggers(
            listOf(
                Trigger(
                    id = "t",
                    steps = listOf(TriggerStep(match = step), TriggerStep(match = step, withinSeconds = 60.0)),
                    actions = listOf(TriggerAction(type = "flush", lookbackSeconds = 60.0)),
                ),
            ),
        )
        assertTrue(engine.observe(event(0, "fail")).isEmpty())
        assertTrue(engine.observe(event(120000, "fail")).isEmpty())
        assertEquals(1, engine.observe(event(130000, "fail")).size)
    }

    @Test
    fun `counted step needs N hits inside the period`() {
        val engine = TriggerEngine()
        engine.setTriggers(
            listOf(
                Trigger(
                    id = "t",
                    steps = listOf(
                        TriggerStep(match = Condition("event.name", "eq", "e"), count = 3, periodSeconds = 10.0),
                    ),
                    actions = listOf(TriggerAction(type = "flush", lookbackSeconds = 60.0)),
                ),
            ),
        )
        assertTrue(engine.observe(event(0, "e")).isEmpty())
        assertTrue(engine.observe(event(20000, "e")).isEmpty())
        assertTrue(engine.observe(event(21000, "e")).isEmpty())
        assertEquals(1, engine.observe(event(22000, "e")).size)
        // The count starts over after a fire.
        assertTrue(engine.observe(event(22001, "e")).isEmpty())
        assertTrue(engine.observe(event(22002, "e")).isEmpty())
        assertEquals(1, engine.observe(event(22003, "e")).size)
    }

    @Test
    fun `a counted step without a period counts every hit`() {
        val engine = TriggerEngine()
        engine.setTriggers(listOf(parseTrigger("""{"id":"t","steps":[{"match":${named("e")},"count":2}],"actions":[{"type":"emit","eventName":"x"}]}""")))
        assertTrue(engine.observe(event(0, "e")).isEmpty())
        assertEquals(1, engine.observe(event(80_000_000, "e")).size)
    }

    @Test
    fun `sampleRate uses the injected random`() {
        val engine = TriggerEngine(random = { 0.9 })
        engine.setTriggers(listOf(defaultExceptionTrigger().copyWith(sampleRate = 0.5)))
        assertTrue(engine.observe(event(0, "exception")).isEmpty())
        assertEquals("sampled_out", engine.skipped[DEFAULT_EXCEPTION_TRIGGER_ID])
        val lucky = TriggerEngine(random = { 0.49 })
        lucky.setTriggers(listOf(defaultExceptionTrigger().copyWith(sampleRate = 0.5)))
        assertEquals(1, lucky.observe(event(0, "exception")).size)
        val never = TriggerEngine(random = { 0.0 })
        never.setTriggers(listOf(defaultExceptionTrigger().copyWith(sampleRate = 0.0)))
        assertTrue(never.observe(event(0, "exception")).isEmpty())
    }

    @Test
    fun `a sampled-out fire does not count towards the limits`() {
        var roll = 0.9
        val engine = TriggerEngine(random = { roll })
        engine.setTriggers(listOf(defaultExceptionTrigger().copyWith(sampleRate = 0.5)))
        assertTrue(engine.observe(event(0, "exception")).isEmpty())
        roll = 0.1
        assertEquals(1, engine.observe(event(1, "exception")).size)
        assertEquals(TriggerLimit(1, 1), engine.sessionLimits[DEFAULT_EXCEPTION_TRIGGER_ID])
    }

    @Test
    fun `disabled and empty triggers are left out`() {
        val engine = TriggerEngine()
        val created = engine.setTriggers(
            listOf(
                defaultExceptionTrigger().copyWith(enabled = false),
                Trigger(id = "no-steps", steps = emptyList(), actions = listOf(TriggerAction(type = "emit", eventName = "x"))),
                Trigger(id = "no-actions", steps = listOf(TriggerStep(match = Condition("a", "exists"))), actions = emptyList()),
                Trigger(id = "ok", steps = listOf(TriggerStep(match = Condition("a", "exists"))), actions = listOf(TriggerAction(type = "emit", eventName = "x"))),
            ),
        )
        assertEquals(listOf("ok"), engine.triggers.map { it.id })
        assertEquals(setOf("ok"), created)
        assertTrue(engine.observe(event(0, "exception")).isEmpty())
    }

    @Test
    fun `setTriggers returns the triggers that start without progress`() {
        val ab = parseTrigger("""{"id":"ab","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""")
        val engine = TriggerEngine()
        assertEquals(setOf("ab"), engine.setTriggers(listOf(ab)))
        engine.observe(event(0, "a"))
        // The same rule again keeps its progress.
        assertTrue(engine.setTriggers(listOf(ab.copyWith(name = "renamed", cooldownSeconds = 9.0))).isEmpty())
        assertEquals(1, engine.observe(event(1, "b")).size)
        engine.observe(event(2, "a"))
        // A changed rule starts over.
        val changed = ab.copyWith(actions = listOf(TriggerAction(type = "emit", eventName = "y")))
        assertEquals(setOf("ab", DEFAULT_EXCEPTION_TRIGGER_ID), engine.setTriggers(listOf(changed, defaultExceptionTrigger())))
        assertTrue(engine.observe(event(3, "b")).isEmpty())
    }

    @Test
    fun `a changed trigger keeps its fire count`() {
        val once = parseTrigger("""{"id":"once","maxPerSession":1,"steps":[{"match":${named("a")}}],"actions":[{"type":"emit","eventName":"x"}]}""")
        val engine = TriggerEngine()
        engine.setTriggers(listOf(once))
        assertEquals(1, engine.observe(event(0, "a")).size)
        engine.setTriggers(listOf(once.copyWith(actions = listOf(TriggerAction(type = "emit", eventName = "y")))))
        assertTrue(engine.observe(event(1, "a")).isEmpty())
        assertEquals("session_clip_limit", engine.skipped["once"])
        // Removed and added back within the session: the session's limits still apply.
        engine.setTriggers(emptyList())
        engine.setTriggers(listOf(once))
        assertTrue(engine.observe(event(2, "a")).isEmpty())
    }

    @Test
    fun `progressVersion changes with progress and with settings`() {
        val engine = TriggerEngine()
        val v0 = engine.progressVersion
        engine.setTriggers(listOf(defaultExceptionTrigger()))
        val v1 = engine.progressVersion
        assertTrue(v1 != v0)
        // A single-step trigger has no progress to save.
        engine.observe(event(0, "exception"))
        assertEquals(v1, engine.progressVersion)
        assertTrue(engine.snapshotProgress().isEmpty())
        engine.resetSession()
        assertTrue(engine.progressVersion != v1)
    }

    @Test
    fun `eventKinds collects the event types a matcher names`() {
        val matcher = AllMatcher(
            listOf(
                EventMatcher("click", "#a"),
                AnyMatcher(listOf(NotMatcher(EventMatcher("hover", "#b")), Condition("x", "exists"))),
                EventMatcher("click", "#c"),
            ),
        )
        assertEquals(setOf("click", "hover"), eventKinds(matcher))
        assertTrue(eventKinds(Condition("x", "exists")).isEmpty())
    }

    @Test
    fun `session limits round trip through JSON and ignore malformed entries`() {
        val engine = TriggerEngine()
        engine.setTriggers(listOf(defaultExceptionTrigger()))
        engine.observe(event(1000, "exception"))
        val saved = Json.parse(Json.stringify(engine.snapshotSessionLimits()))
        assertEquals(mapOf(DEFAULT_EXCEPTION_TRIGGER_ID to mapOf("fires" to 1L, "lastFireMs" to 1000L)), saved)

        val next = TriggerEngine()
        next.setTriggers(listOf(defaultExceptionTrigger()))
        next.restoreSessionLimits(saved)
        assertEquals(TriggerLimit(1, 1000), next.sessionLimits[DEFAULT_EXCEPTION_TRIGGER_ID])
        assertTrue(next.observe(event(2000, "exception")).isEmpty())
        assertEquals("cooldown", next.skipped[DEFAULT_EXCEPTION_TRIGGER_ID])

        val other = TriggerEngine()
        other.restoreSessionLimits(
            Json.parse("""{"a":{"fires":-1,"lastFireMs":0},"b":{"fires":1.5,"lastFireMs":0},"c":{"fires":1},"d":"x","e":{"fires":2,"lastFireMs":5}}"""),
        )
        other.restoreSessionLimits(listOf(1, 2))
        assertEquals(setOf("e"), other.sessionLimits.keys)
        // Limits restored before the trigger is known apply once it is.
        other.setTriggers(listOf(defaultExceptionTrigger().withId("e")))
        assertEquals(TriggerLimit(2, 5), other.sessionLimits["e"])
    }

    private fun Trigger.withId(id: String) = Trigger(
        id = id,
        name = name,
        enabled = enabled,
        sampleRate = sampleRate,
        cooldownSeconds = cooldownSeconds,
        maxPerSession = maxPerSession,
        steps = steps,
        actions = actions,
    )

    @Test
    fun `restoreProgress accepts only its own format`() {
        val ab = parseTrigger("""{"id":"ab","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""")
        val engine = TriggerEngine(now = { 100L })
        engine.setTriggers(listOf(ab))
        engine.observe(event(50, "a"))
        val good = engine.snapshotProgress()
        assertEquals(1, good.size)
        val item = good.single() as Map<*, *>
        assertEquals("ab", item["id"])
        assertEquals(2L, item["v"])
        assertEquals(ab.signature, item["signature"])

        fun restoredFires(saved: Any?, only: Set<String>? = null): Int {
            val next = TriggerEngine(now = { 100L })
            next.setTriggers(listOf(ab))
            next.restoreProgress(saved, only = only)
            return next.observe(event(100, "b")).size
        }
        assertEquals(1, restoredFires(good))
        assertEquals(1, restoredFires(Json.parse(Json.stringify(good))))
        assertEquals(1, restoredFires(good, only = setOf("ab")))
        assertEquals(0, restoredFires(good, only = setOf("other")))
        assertEquals(0, restoredFires(null))
        assertEquals(0, restoredFires(mapOf("x" to 1)))
        fun with(key: String, value: Any?) = listOf(LinkedHashMap(item).apply { put(key, value) })
        assertEquals(0, restoredFires(with("v", 1L)))
        assertEquals(1, restoredFires(with("v", 2.0)))
        assertEquals(0, restoredFires(with("signature", "other")))
        assertEquals(0, restoredFires(with("id", "zz")))
        assertEquals(0, restoredFires(with("steps", listOf(item["steps"]))))
        assertEquals(0, restoredFires(with("steps", "x")))
        // An anchor in the future, or with a fractional start, is not progress.
        assertEquals(0, restoredFires(with("steps", listOf(mapOf("anchors" to emptyList<Any>(), "hits" to emptyList<Any>()), mapOf("anchors" to listOf(listOf(150L, 0L)), "hits" to emptyList<Any>())))))
        assertEquals(0, restoredFires(with("steps", listOf(mapOf<String, Any>(), mapOf("anchors" to listOf(listOf(50L, 0.5)))))))
        assertEquals(1, restoredFires(with("steps", listOf(mapOf<String, Any>(), mapOf("anchors" to listOf("junk", listOf(50.9, 0L)))))))
    }
}
