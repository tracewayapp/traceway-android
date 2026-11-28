package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser SDK's sequence-trigger tests (js-client
 * packages/core/src/sequence-triggers.test.ts), by way of the Flutter SDK's
 * port, so every engine is held to the same behavior.
 */
class SequenceTriggersTest {

    private val journey = parseTrigger(
        """{"id":"journey","maxPerSession":1,
          "steps":[
            {"match":{"event":{"type":"click","value":"#users .open"}}},
            {"match":{"event":{"type":"navigation","value":"/users/{id}"}},"withinSeconds":10},
            {"match":{"event":{"type":"log","value":"loaded"}},"withinSeconds":5}],
          "actions":[{"type":"capture","replay":true,"durationSeconds":30}]}""",
    )

    private fun engineWith(vararg triggers: Trigger) = TriggerEngine().apply { setTriggers(triggers.toList()) }

    private fun roundTrip(value: Any?): Any? = Json.parse(Json.stringify(value))

    private fun run(steps: String, events: List<Pair<Long, String>>): List<Int> {
        val engine = engineWith(parseTrigger("""{"id":"t","steps":$steps,"actions":[{"type":"emit","eventName":"x"}]}"""))
        return events.map { (time, name) -> engine.observe(observed(time, mapOf("event.name" to name))).size }
    }

    private fun tap(timeMs: Long, test: (String) -> Boolean, sequence: String? = null) =
        observed(timeMs, mapOf(KEY_POINTER_ACTION to "click", KEY_TARGET_MATCHES to test), sequence)

    @Test
    fun `restores a click across an app restart, fires once, and ignores unrelated logs`() {
        val first = engineWith(journey)
        first.observe(tap(1000, { it == "#users .open" }))
        val next = engineWith(journey)
        next.restoreProgress(roundTrip(first.snapshotProgress()), 2000)
        assertTrue(
            next.observe(
                observed(
                    2000,
                    mapOf(
                        "event.name" to "browser.navigation",
                        "attributes.url.full" to "https://app.test/users/42?secret=hidden",
                    ),
                ),
            ).isEmpty(),
        )
        assertTrue(next.observe(observed(2100, mapOf("event.name" to "browser.console", "body" to "other"))).isEmpty())
        assertEquals(1, next.observe(observed(2200, mapOf("event.name" to "browser.console", "body" to "loaded"))).size)
        assertTrue(next.observe(observed(2300, mapOf("event.name" to "browser.console", "body" to "loaded"))).isEmpty())
    }

    @Test
    fun `rejects expired and changed-rule progress`() {
        val engine = engineWith(journey)
        engine.observe(tap(1000, { true }))
        val changed = journey.copyWith(actions = listOf(TriggerAction(type = "startReplay", durationSeconds = 10.0)))
        for ((rule, now) in listOf(journey to 12000L, changed to 2000L)) {
            val restored = engineWith(rule)
            restored.restoreProgress(engine.snapshotProgress(), now)
            restored.observe(observed(now, mapOf("event.name" to "browser.navigation", "attributes.url.full" to "/users/42")))
            assertTrue(
                restored.observe(observed(now + 1, mapOf("event.name" to "browser.console", "body" to "loaded"))).isEmpty(),
            )
        }
    }

    @Test
    fun `a trigger with a malformed event step is dropped whole`() {
        val settings = applyOverlay(
            defaultSettings(),
            Json.parse(
                """{"triggers":[{"id":"bad",
                  "steps":[{"match":{"event":{"type":"click","value":"a:hover"}}}],
                  "actions":[{"type":"emit","eventName":"x"}]}]}""",
            ) as JsonObject,
        )
        assertEquals(listOf("default-exception"), settings.triggers.map { it.id })
    }

    @Test
    fun `steps are reported in the schema the server validates`() {
        val steps = roundTrip(journey.stepsJson()) as List<*>
        assertEquals(
            mapOf(
                "match" to mapOf("event" to mapOf("type" to "click", "value" to "#users .open")),
                "withinSeconds" to null,
            ),
            steps[0],
        )
        assertEquals(10.0, ((steps[1] as Map<*, *>)["withinSeconds"] as Number).toDouble(), 0.0)
    }

    @Test
    fun `keeps progress when the first step happens again`() {
        assertEquals(
            listOf(0, 0, 0, 1),
            run(
                """[{"match":${named("a")}},{"match":${named("b")}},{"match":${named("c")}}]""",
                listOf(1L to "a", 2L to "b", 3L to "a", 4L to "c"),
            ),
        )
    }

    @Test
    fun `fires an action after an error storm that keeps going`() {
        assertEquals(
            listOf(0, 0, 0, 0, 1),
            run(
                """[{"match":${named("err")},"count":3,"periodSeconds":10},{"match":${named("retry")},"withinSeconds":5}]""",
                listOf(0L to "err", 100L to "err", 200L to "err", 300L to "err", 400L to "retry"),
            ),
        )
    }

    @Test
    fun `measures a timeout from the latest earlier step`() {
        val steps = """[{"match":${named("a")}},{"match":${named("b")},"withinSeconds":5}]"""
        assertEquals(listOf(0, 0, 1), run(steps, listOf(0L to "a", 4000L to "a", 8000L to "b")))
        assertEquals(listOf(0, 0), run(steps, listOf(0L to "a", 6000L to "b")))
    }

    @Test
    fun `counts a later step only from the moment the earlier one completed`() {
        assertEquals(
            listOf(0, 0, 0, 0, 1),
            run(
                """[{"match":${named("a")}},{"match":${named("b")},"count":2,"withinSeconds":10}]""",
                listOf(0L to "b", 1L to "a", 2L to "b", 3L to "a", 3L to "b"),
            ),
        )
    }

    @Test
    fun `does not let one event complete two steps`() {
        assertEquals(
            listOf(0, 1, 0, 1),
            run(
                """[{"match":${named("a")}},{"match":${named("a")}}]""",
                listOf(0L to "a", 0L to "a", 0L to "a", 0L to "a"),
            ),
        )
    }

    @Test
    fun `a counted later step needs its hits inside the period`() {
        assertEquals(
            listOf(0, 0, 0, 1),
            run(
                """[{"match":${named("a")}},{"match":${named("b")},"count":2,"periodSeconds":1}]""",
                listOf(0L to "a", 100L to "b", 2000L to "b", 2500L to "b"),
            ),
        )
    }

    @Test
    fun `carries a count on the first step across app starts`() {
        val counted = parseTrigger(
            """{"id":"burst","steps":[{"match":${named("err")},"count":3,"periodSeconds":60}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val first = engineWith(counted)
        first.observe(observed(1000, mapOf("event.name" to "err")))
        first.observe(observed(2000, mapOf("event.name" to "err")))
        val next = engineWith(counted)
        next.restoreProgress(roundTrip(first.snapshotProgress()), 3000)
        assertEquals(1, next.observe(observed(3000, mapOf("event.name" to "err"))).size)
    }

    @Test
    fun `restored hits outside the period are dropped`() {
        val counted = parseTrigger(
            """{"id":"burst","steps":[{"match":${named("err")},"count":3,"periodSeconds":60}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val first = engineWith(counted)
        first.observe(observed(1000, mapOf("event.name" to "err")))
        first.observe(observed(2000, mapOf("event.name" to "err")))
        val next = engineWith(counted)
        next.restoreProgress(roundTrip(first.snapshotProgress()), 61_500)
        assertTrue(next.observe(observed(61_500, mapOf("event.name" to "err"))).isEmpty())
        assertEquals(1, next.observe(observed(61_600, mapOf("event.name" to "err"))).size)
    }

    @Test
    fun `keeps a step without a timeout for as long as the session`() {
        val open = parseTrigger(
            """{"id":"open","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val first = engineWith(open)
        first.observe(observed(0, mapOf("event.name" to "a")))
        val next = engineWith(open)
        next.restoreProgress(first.snapshotProgress(), 3_600_000)
        assertEquals(1, next.observe(observed(3_600_000, mapOf("event.name" to "b"))).size)
        // But not past a day.
        val late = engineWith(open)
        late.restoreProgress(first.snapshotProgress(), 86_400_001)
        assertTrue(late.observe(observed(86_400_001, mapOf("event.name" to "b"))).isEmpty())
    }

    @Test
    fun `matches a tap only against steps that name a click`() {
        val engine = engineWith(
            parseTrigger(
                """{"id":"negated","steps":[{"match":{"not":${named("exception")}}}],"actions":[{"type":"emit","eventName":"x"}]}""",
            ),
            parseTrigger(
                """{"id":"pro-click","steps":[{"match":{"all":[
                    {"event":{"type":"click","value":"#buy"}},
                    {"key":"global.plan","op":"eq","value":"pro"}]}}],
                  "actions":[{"type":"emit","eventName":"x"}]}""",
            ),
        )
        val fired = engine.observe(
            observed(
                0,
                mapOf(KEY_POINTER_ACTION to "click", KEY_TARGET_MATCHES to { _: String -> true }, "global.plan" to "pro"),
                sequence = "click",
            ),
        )
        assertEquals(listOf("pro-click"), fired.map { it.trigger.id })
    }

    @Test
    fun `only reports a change of progress when there is one`() {
        val engine = engineWith(
            parseTrigger(
                """{"id":"t","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""",
            ),
        )
        val before = engine.progressVersion
        engine.observe(observed(0, mapOf("event.name" to "other")))
        assertEquals(before, engine.progressVersion)
        engine.observe(observed(1, mapOf("event.name" to "a")))
        assertTrue(engine.progressVersion != before)
    }

    @Test
    fun `a snapshot does not change with later progress`() {
        val counted = parseTrigger(
            """{"id":"burst","steps":[{"match":${named("a")}},{"match":${named("err")},"count":5,"periodSeconds":1}],
              "actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val engine = engineWith(counted)
        engine.observe(observed(0, mapOf("event.name" to "a")))
        engine.observe(observed(100, mapOf("event.name" to "err")))
        val snapshot = engine.snapshotProgress()
        val saved = Json.stringify(snapshot)
        for (t in 2000L until 2600L step 100) engine.observe(observed(t, mapOf("event.name" to "err")))
        assertEquals(saved, Json.stringify(snapshot))
        assertTrue(saved != Json.stringify(engine.snapshotProgress()))
    }

    @Test
    fun `fire counts survive a restart within the session`() {
        val once = parseTrigger(
            """{"id":"once","maxPerSession":1,"steps":[{"match":${named("a")}}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val first = engineWith(once)
        assertEquals(1, first.observe(observed(0, mapOf("event.name" to "a"))).size)
        val next = engineWith(once)
        next.restoreSessionLimits(roundTrip(first.snapshotSessionLimits()))
        assertTrue(next.observe(observed(1, mapOf("event.name" to "a"))).isEmpty())
        assertEquals("session_clip_limit", next.skipped["once"])
    }

    @Test
    fun `a fired sequence starts over and a new session forgets progress`() {
        val ab = parseTrigger(
            """{"id":"ab","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val engine = engineWith(ab)
        engine.observe(observed(0, mapOf("event.name" to "a")))
        assertEquals(1, engine.observe(observed(1, mapOf("event.name" to "b"))).size)
        assertTrue(engine.observe(observed(2, mapOf("event.name" to "b"))).isEmpty())
        assertTrue(engine.snapshotProgress().isEmpty())
        engine.observe(observed(3, mapOf("event.name" to "a")))
        assertEquals(1, engine.snapshotProgress().size)
        engine.resetSession()
        assertTrue(engine.snapshotProgress().isEmpty())
        assertTrue(engine.sessionLimits.isEmpty())
        assertTrue(engine.observe(observed(4, mapOf("event.name" to "b"))).isEmpty())
    }

    @Test
    fun `restoring only new triggers leaves running ones alone`() {
        // The client restores saved progress for the triggers setTriggers reports
        // as new; one already running keeps its newer progress.
        val ab = parseTrigger(
            """{"id":"ab","steps":[{"match":${named("a")}},{"match":${named("b")}}],"actions":[{"type":"emit","eventName":"x"}]}""",
        )
        val engine = engineWith(ab)
        engine.observe(observed(0, mapOf("event.name" to "a")))
        val saved = engine.snapshotProgress()
        assertEquals(1, engine.observe(observed(1, mapOf("event.name" to "b"))).size)
        val created = engine.setTriggers(listOf(ab))
        engine.restoreProgress(saved, 2, only = created)
        assertTrue(engine.observe(observed(2, mapOf("event.name" to "b"))).isEmpty())
    }
}
