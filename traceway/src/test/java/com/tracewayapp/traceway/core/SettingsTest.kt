package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.TracewayImagePolicy
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.asJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsTest {

    private fun overlay(json: String): JsonObject = Json.parse(json).asJsonObject()!!

    @Test
    fun `defaults buffer breadcrumbs and stream errors`() {
        val s = defaultSettings()
        assertEquals(Mode.STREAM, s.signal("errors").mode)
        assertEquals(Mode.STREAM, s.signal("messages").mode)
        assertEquals(Mode.BUFFER, s.signal("console").mode)
        assertEquals("info", s.signal("console").minSeverity)
        assertEquals(Mode.BUFFER, s.signal("network").mode)
        assertEquals(Mode.OFF, s.signal("longTasks").mode)
        assertEquals(Mode.BUFFER, s.replayMode)
        assertEquals(2L * 1024 * 1024, s.bufferMaxBytes)
        assertEquals(600.0, s.bufferMaxAgeSeconds, 0.0)
        assertEquals(600_000L, s.bufferMaxAgeMs)
        assertEquals(listOf(DEFAULT_EXCEPTION_TRIGGER_ID), s.triggers.map { it.id })
        assertEquals(SIGNAL_NAMES, s.signals.keys.toList())
    }

    @Test
    fun `recordAllSessions streams breadcrumbs and replay`() {
        val s = defaultSettings(baselineSampleRate = 7.0, recordAllSessions = true)
        assertEquals(Mode.STREAM, s.signal("console").mode)
        assertEquals(Mode.STREAM, s.signal("custom").mode)
        assertEquals(Mode.BUFFER, s.signal("webVitals").mode)
        assertEquals(Mode.STREAM, s.replayMode)
        assertEquals(1.0, s.baselineSampleRate, 0.0)
        assertEquals(0.0, defaultSettings(baselineSampleRate = Double.NaN).baselineSampleRate, 0.0)
    }

    @Test
    fun `copy does not share mutable state`() {
        val s = defaultSettings()
        val c = s.copy()
        c.signal("console").mode = Mode.OFF
        c.metrics.enabled = false
        c.triggers.clear()
        assertEquals(Mode.BUFFER, s.signal("console").mode)
        assertTrue(s.metrics.enabled)
        assertEquals(1, s.triggers.size)
    }

    @Test
    fun `overlay merges known fields and ignores malformed ones`() {
        val merged = applyOverlay(
            defaultSettings(),
            parseSettingsDocument(
                """{"schemaVersion":1,
                  "signals":{"custom":{"mode":"stream","sampleRate":7},
                             "console":{"mode":"nope","minSeverity":"warn"}},
                  "sessions":{"baselineSampleRate":0.25},
                  "buffer":{"maxBytes":1e12,"maxAgeSeconds":30},
                  "unknown":{"x":1},
                  "triggers":[{"id":"default-exception","enabled":false},
                              {"id":"t2","steps":[{"match":{"key":"event.name","op":"eq","value":"x"}}],
                               "actions":[{"type":"flush","lookbackSeconds":60,"signals":["custom","bogus"]}]},
                              {"id":"bad","steps":[],"actions":[]}],
                  "captures":[{"id":"c1","until":"2099-01-01T00:00:00Z","lookbackSeconds":0},
                              {"id":"c2","until":"not a date"}]}""",
            ),
        )
        assertEquals(Mode.STREAM, merged.signal("custom").mode)
        assertEquals(1.0, merged.signal("custom").sampleRate, 0.0)
        assertEquals(Mode.BUFFER, merged.signal("console").mode)
        assertEquals("warn", merged.signal("console").minSeverity)
        assertEquals(0.25, merged.baselineSampleRate, 0.0)
        assertEquals(64L * 1024 * 1024, merged.bufferMaxBytes)
        assertEquals(30.0, merged.bufferMaxAgeSeconds, 0.0)
        assertEquals(listOf("default-exception", "t2"), merged.triggers.map { it.id })
        assertEquals(false, merged.triggers[0].enabled)
        assertEquals(1, merged.triggers[0].steps.size)
        assertEquals(listOf("custom"), merged.triggers[1].actions.single().signals)
        assertEquals("c1", merged.captures.single().id)
        assertEquals(0.0, merged.captures.single().lookbackSeconds!!, 0.0)
        assertEquals(4070908800000L, merged.captures.single().untilMs)
    }

    @Test
    fun `overlay leaves the base untouched`() {
        val base = defaultSettings()
        applyOverlay(base, overlay("""{"enabled":false,"signals":{"console":{"mode":"off"}}}"""))
        assertTrue(base.enabled)
        assertEquals(Mode.BUFFER, base.signal("console").mode)
    }

    @Test
    fun `overlay reads sessions, replay, metrics and buffer`() {
        val merged = applyOverlay(
            defaultSettings(),
            overlay(
                """{"enabled":false,
                  "sessions":{"enabled":false,"baselineSampleRate":-3},
                  "replay":{"mode":"stream","sessionSampleRate":0.5,"maskAllInputs":false,"captureMode":"video"},
                  "metrics":{"enabled":false,"sampleRate":0.1,"flushSeconds":5},
                  "buffer":{"maxBytes":1000.9,"maxAgeSeconds":-1,"persist":false},
                  "signals":{"console":{"minSeverity":"loud","sampleRate":"0.5"}}}""",
            ),
        )
        assertFalse(merged.enabled)
        assertFalse(merged.sessionsEnabled)
        assertEquals(0.0, merged.baselineSampleRate, 0.0)
        assertEquals(Mode.STREAM, merged.replayMode)
        assertEquals(0.5, merged.replaySessionSampleRate, 0.0)
        assertFalse(merged.replayMaskAllInputs)
        assertFalse(merged.metrics.enabled)
        assertEquals(0.1, merged.metrics.sampleRate, 0.0)
        assertEquals(MIN_METRICS_FLUSH_SECONDS, merged.metrics.flushSeconds, 0.0)
        assertEquals(1000L, merged.bufferMaxBytes)
        assertEquals(600.0, merged.bufferMaxAgeSeconds, 0.0)
        assertFalse(merged.persist)
        assertEquals("info", merged.signal("console").minSeverity)
        assertEquals(1.0, merged.signal("console").sampleRate, 0.0)

        val longFlush = applyOverlay(defaultSettings(), overlay("""{"metrics":{"flushSeconds":99999}}"""))
        assertEquals(MAX_METRICS_FLUSH_SECONDS, longFlush.metrics.flushSeconds, 0.0)
    }

    @Test
    fun `a trigger overlay merges into the trigger with the same id`() {
        val merged = applyOverlay(
            defaultSettings(),
            overlay(
                """{"triggers":[
                  {"id":"default-exception","sampleRate":2,"cooldownSeconds":0,"maxPerSession":3.7,"name":7},
                  {"id":"","steps":[{"match":{"key":"a","op":"exists"}}],"actions":[{"type":"emit","eventName":"x"}]},
                  {"id":"no-actions","steps":[{"match":{"key":"a","op":"exists"}}]},
                  "junk"]}""",
            ),
        )
        assertEquals(listOf(DEFAULT_EXCEPTION_TRIGGER_ID), merged.triggers.map { it.id })
        val t = merged.triggers.single()
        assertEquals(1.0, t.sampleRate!!, 0.0)
        assertEquals(0.0, t.cooldownSeconds!!, 0.0)
        assertEquals(3, t.maxPerSession)
        assertEquals("Error clip", t.name)
        assertEquals(defaultExceptionTrigger().signature, t.signature)
    }

    @Test
    fun `steps parse counts, periods and timeouts`() {
        val merged = applyOverlay(
            defaultSettings(),
            overlay(
                """{"triggers":[{"id":"t","steps":[
                  {"match":{"key":"a","op":"exists"},"count":3,"periodSeconds":100000},
                  {"match":{"key":"a","op":"exists"},"count":2.5,"periodSeconds":5,"withinSeconds":-1},
                  {"match":{"key":"a","op":"exists"},"count":5000,"withinSeconds":4.5},
                  {"match":{"key":"a","op":"exists"},"count":1,"periodSeconds":5}],
                  "actions":[{"type":"emit","eventName":"x","attributes":{"k":"v","n":1}}]}]}""",
            ),
        )
        val steps = merged.triggers.last().steps
        assertEquals(3, steps[0].count)
        assertEquals(86400.0, steps[0].periodSeconds!!, 0.0)
        assertNull(steps[1].count)
        assertNull(steps[1].periodSeconds)
        assertNull(steps[1].withinSeconds)
        assertEquals(1000, steps[2].count)
        assertNull(steps[2].periodSeconds)
        assertEquals(4.5, steps[2].withinSeconds!!, 0.0)
        assertNull(steps[3].count)
        assertNull(steps[3].periodSeconds)
        assertEquals(mapOf("k" to "v"), merged.triggers.last().actions.single().attributes)
    }

    @Test
    fun `actions are validated by type`() {
        fun actions(json: String): List<TriggerAction>? {
            val merged = applyOverlay(
                defaultSettings(),
                overlay("""{"triggers":[{"id":"t","steps":[{"match":{"key":"a","op":"exists"}}],"actions":$json}]}"""),
            )
            return merged.triggers.firstOrNull { it.id == "t" }?.actions
        }
        val ok = actions(
            """[{"type":"flush","lookbackSeconds":1e9,"replay":true},
                {"type":"stream","durationSeconds":10,"signals":["*","console"]},
                {"type":"startReplay","durationSeconds":5,"signals":["console"]},
                {"type":"capture","durationSeconds":30,"lookbackSeconds":-1},
                {"type":"emit","eventName":"x"}]""",
        )!!
        assertEquals(listOf("flush", "stream", "startReplay", "capture", "emit"), ok.map { it.type })
        assertEquals(86400.0, ok[0].lookbackSeconds!!, 0.0)
        assertTrue(ok[0].replay)
        assertNull(ok[0].signals)
        assertEquals(listOf("*", "console"), ok[1].signals)
        assertNull(ok[2].signals)
        assertNull(ok[3].lookbackSeconds)
        for (bad in listOf(
            """[{"type":"flush"}]""",
            """[{"type":"stream","durationSeconds":0}]""",
            """[{"type":"startReplay"}]""",
            """[{"type":"capture","lookbackSeconds":5}]""",
            """[{"type":"emit","eventName":""}]""",
            """[{"type":"explode"}]""",
            """[]""",
            """{}""",
        )) {
            assertNull(bad, actions(bad))
        }
    }

    @Test
    fun `matchers parse conditions and combinations`() {
        fun parse(json: String) = parseMatcher(Json.parse(json))
        val c = parse("""{"key":"attributes.status","op":"in","values":[500,"503",true]}""") as Condition
        assertEquals(listOf(500L, "503", true), c.values)
        assertTrue(parse("""{"all":[{"key":"a","op":"exists"}],"any":[]}""") is AllMatcher)
        assertTrue(parse("""{"all":"x","any":[{"key":"a","op":"exists"}]}""") is AnyMatcher)
        assertTrue(parse("""{"not":{"key":"a","op":"exists"}}""") is NotMatcher)
        for (bad in listOf(
            """{"key":"","op":"eq"}""",
            """{"key":"a","op":"regex"}""",
            """{"key":"a","op":"eq","value":{"x":1}}""",
            """{"key":"a","op":"in","values":[1,[2]]}""",
            """{"key":"a","op":"in","values":"1"}""",
            """{"not":{"key":"a","op":"nope"}}""",
            """{"all":[{"key":"a","op":"exists"},{}]}""",
            """"string"""",
        )) {
            assertNull(bad, parse(bad))
        }
        var deep = """{"key":"a","op":"exists"}"""
        repeat(8) { deep = """{"not":$deep}""" }
        assertTrue(parse(deep) is NotMatcher)
        assertNull(parse("""{"not":$deep}"""))
    }

    @Test
    fun `rejects documents it cannot apply`() {
        for (body in listOf("nope", "[]", """{"schemaVersion":2}""", """{"schemaVersion":"1"}""", "")) {
            try {
                parseSettingsDocument(body)
                fail("accepted $body")
            } catch (_: SettingsError) {
            }
        }
        assertEquals(1.0, (parseSettingsDocument("""{"schemaVersion":1.0}""")["schemaVersion"] as Number).toDouble(), 0.0)
    }

    @Test
    fun `captures are replaced as a whole`() {
        val base = applyOverlay(
            defaultSettings(),
            overlay("""{"captures":[{"id":"c1","until":"2099-01-01T00:00:00Z","signals":["console"],"replay":true}]}"""),
        )
        val c = base.captures.single()
        assertEquals(listOf("console"), c.signals)
        assertTrue(c.replay)
        assertNull(c.lookbackSeconds)
        assertTrue(applyOverlay(base, overlay("""{"captures":[]}""")).captures.isEmpty())
        assertEquals(1, applyOverlay(base, overlay("""{"captures":"x"}""")).captures.size)
    }

    @Test
    fun `limits lower the ceilings and clamp holds modes under them`() {
        val app = ceilingsFrom(screenCapture = true, captureNetwork = false)
        val limited = applyLimits(
            app,
            overlay("""{"limits":{"signals":{"custom":"buffer","console":"stream","bogus":"off"},"replay":"off","metrics":"off"}}"""),
        )
        assertEquals(Mode.BUFFER, limited.signals["custom"])
        assertEquals(Mode.STREAM, limited.signals["console"])
        assertEquals(Mode.OFF, limited.signals["network"])
        assertEquals(Mode.OFF, limited.replay)
        assertFalse(limited.metrics)
        assertTrue(applyLimits(app, null) === app)
        assertTrue(applyLimits(app, overlay("{}")) === app)

        val clamped = clampToCeilings(defaultSettings(recordAllSessions = true), limited)
        assertEquals(Mode.BUFFER, clamped.signal("custom").mode)
        assertEquals(Mode.OFF, clamped.signal("network").mode)
        assertEquals(Mode.STREAM, clamped.signal("console").mode)
        assertEquals(Mode.OFF, clamped.replayMode)
        assertFalse(clamped.metrics.enabled)

        val off = clampToCeilings(defaultSettings(), ceilingsFrom(enabled = false, sessions = false))
        assertFalse(off.enabled)
        assertFalse(off.sessionsEnabled)
    }

    @Test
    fun `replay images default to wifi, follow the documents and stay under the app's ceiling`() {
        val defaults = defaultSettings()
        assertEquals(TracewayImagePolicy.WIFI, defaults.replayImages)
        val always = applyOverlay(defaults, overlay("""{"replay":{"images":"always"}}"""))
        assertEquals(TracewayImagePolicy.ALWAYS, always.replayImages)
        assertEquals(TracewayImagePolicy.WIFI, defaults.replayImages)
        assertEquals(TracewayImagePolicy.WIFI, always.copy().also { it.replayImages = TracewayImagePolicy.WIFI }.replayImages)
        assertEquals(TracewayImagePolicy.ALWAYS, always.copy().replayImages)
        for (bad in listOf("\"sometimes\"", "\"WIFI\"", "1", "true", "null")) {
            assertEquals(bad, TracewayImagePolicy.ALWAYS, applyOverlay(always, overlay("""{"replay":{"images":$bad}}""")).replayImages)
        }
        val off = applyOverlay(always, overlay("""{"replay":{"images":"off"}}"""))
        assertEquals(TracewayImagePolicy.OFF, off.replayImages)

        // The lower of the setting and the app's ceiling.
        assertEquals(TracewayImagePolicy.ALWAYS, clampToCeilings(always, ceilingsFrom(screenCapture = true)).replayImages)
        val wifiCeiling = ceilingsFrom(screenCapture = true, replayImages = TracewayImagePolicy.WIFI)
        assertEquals(TracewayImagePolicy.WIFI, clampToCeilings(always, wifiCeiling).replayImages)
        assertEquals(TracewayImagePolicy.OFF, clampToCeilings(off, wifiCeiling).replayImages)
        val offCeiling = ceilingsFrom(screenCapture = true, replayImages = TracewayImagePolicy.OFF)
        assertEquals(TracewayImagePolicy.OFF, clampToCeilings(always, offCeiling).replayImages)
        // No replay, no pictures: the app records no screen, or the plan allows no replay.
        assertEquals(TracewayImagePolicy.OFF, clampToCeilings(always, ceilingsFrom(screenCapture = false)).replayImages)
        val limited = applyLimits(wifiCeiling, overlay("""{"limits":{"replay":"off"}}"""))
        assertEquals(TracewayImagePolicy.WIFI, limited.replayImages)
        assertEquals(TracewayImagePolicy.OFF, clampToCeilings(always, limited).replayImages)

        // A rule over the app's local document, like maskAllInputs.
        val control = ControlState(always, ceilingsFrom(screenCapture = true))
        assertEquals(TracewayImagePolicy.ALWAYS, control.settings.replayImages)
        control.applyRemote(overlay("""{"replay":{"images":"wifi"}}"""))
        assertEquals(TracewayImagePolicy.WIFI, control.settings.replayImages)
        control.applyRemote(overlay("""{"replay":{"mode":"stream"}}"""))
        assertEquals(TracewayImagePolicy.ALWAYS, control.settings.replayImages)
        assertEquals(TracewayImagePolicy.OFF, TracewayImagePolicy.lower(TracewayImagePolicy.OFF, TracewayImagePolicy.ALWAYS))
        assertEquals("wifi", TracewayImagePolicy.WIFI.wire)
    }

    @Test
    fun `ceilings follow the init options`() {
        val c = ceilingsFrom(
            captureLogs = false,
            captureNavigation = false,
            captureUserActions = false,
            captureMetrics = false,
        )
        assertEquals(Mode.OFF, c.signals["console"])
        assertEquals(Mode.OFF, c.signals["navigation"])
        assertEquals(Mode.OFF, c.signals["userActions"])
        assertEquals(Mode.STREAM, c.signals["network"])
        assertEquals(Mode.STREAM, c.signals["errors"])
        assertEquals(Mode.OFF, c.replay)
        assertFalse(c.metrics)
        assertEquals(Mode.BUFFER, lowerMode(Mode.STREAM, Mode.BUFFER))
        assertEquals(Mode.OFF, lowerMode(Mode.OFF, Mode.STREAM))
        assertEquals(Mode.STREAM, Mode.from("stream"))
        assertNull(Mode.from("STREAM"))
        assertEquals("buffer", Mode.BUFFER.wire)
    }

    @Test
    fun `the signature changes with steps and actions only`() {
        val t = defaultExceptionTrigger()
        assertEquals(t.signature, t.copyWith(name = "x", cooldownSeconds = 1.0, maxPerSession = 1).signature)
        val other = t.copyWith(actions = listOf(TriggerAction(type = "emit", eventName = "x")))
        assertTrue(t.signature != other.signature)
        val steps = Json.parse(Json.stringify(t.stepsJson())) as List<*>
        assertEquals(
            mapOf(
                "match" to mapOf("key" to "event.name", "op" to "eq", "value" to "exception"),
                "withinSeconds" to null,
            ),
            steps.single(),
        )
    }
}
