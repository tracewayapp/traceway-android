package com.tracewayapp.traceway.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStateTest {

    @Test
    fun `remote settings cannot exceed the init ceilings`() {
        val control = ControlState(defaultSettings(), ceilingsFrom(screenCapture = false, captureNetwork = false))
        control.applyRemote(parseSettingsDocument("""{"signals":{"network":{"mode":"stream"}},"replay":{"mode":"stream"}}"""))
        assertEquals(Mode.OFF, control.modeFor("network"))
        assertFalse(control.canExport("network"))
        assertEquals(Mode.OFF, control.replayMode)
    }

    @Test
    fun `replay streams for a rule or an override that asks for it`() {
        var now = 0L
        val control = ControlState(defaultSettings(), ceilingsFrom(screenCapture = true), now = { now })
        assertEquals(Mode.BUFFER, control.replayMode)
        control.addOverride(StreamOverride(signals = listOf("*"), untilMs = 1000))
        assertEquals(Mode.BUFFER, control.replayMode)
        control.addOverride(StreamOverride(signals = emptyList(), untilMs = 2000, replay = true))
        assertEquals(Mode.STREAM, control.replayMode)
        assertEquals(1000L, control.nextOverrideExpiryMs)
        now = 2001
        assertEquals(Mode.BUFFER, control.replayMode)
        assertNull(control.nextOverrideExpiryMs)
        control.applyRemote(parseSettingsDocument("""{"replay":{"mode":"stream","sessionSampleRate":1}}"""))
        assertEquals(Mode.STREAM, control.replayMode)
        control.applyRemote(parseSettingsDocument("""{"replay":{"mode":"off"}}"""))
        assertEquals(Mode.OFF, control.replayMode)
    }

    @Test
    fun `an override cannot stream replay past the ceiling`() {
        val control = ControlState(defaultSettings(), ceilingsFrom())
        control.addOverride(StreamOverride(signals = emptyList(), untilMs = 1L shl 50, replay = true))
        assertEquals(Mode.OFF, control.replayMode)
    }

    @Test
    fun `plan limits lower the ceilings`() {
        val control = ControlState(defaultSettings(), ceilingsFrom(screenCapture = true))
        control.applyRemote(
            parseSettingsDocument(
                """{"signals":{"custom":{"mode":"stream"}},"limits":{"signals":{"custom":"buffer"},"replay":"off"}}""",
            ),
        )
        assertEquals(Mode.BUFFER, control.modeFor("custom"))
        assertFalse(control.canExport("custom"))
        assertEquals(Mode.OFF, control.replayMode)
        // An override cannot go past the plan either.
        control.addOverride(StreamOverride(signals = listOf("custom"), untilMs = Long.MAX_VALUE, replay = true))
        assertEquals(Mode.BUFFER, control.modeFor("custom"))
        assertEquals(Mode.OFF, control.replayMode)
        // Falling back to defaults lifts the plan's limits.
        control.applyRemote(null)
        assertEquals(Mode.STREAM, control.modeFor("custom"))
    }

    @Test
    fun `stream overrides expire`() {
        var now = 0L
        val control = ControlState(defaultSettings(), ceilingsFrom(), now = { now })
        control.addOverride(StreamOverride(signals = listOf("console"), untilMs = 1000))
        assertEquals(Mode.STREAM, control.modeFor("console"))
        assertEquals(Mode.BUFFER, control.modeFor("network"))
        now = 1001
        assertEquals(Mode.BUFFER, control.modeFor("console"))
    }

    @Test
    fun `a star override covers signals that are on, and a capture also those rules switched off`() {
        val control = ControlState(defaultSettings(), ceilingsFrom())
        control.applyRemote(
            parseSettingsDocument("""{"signals":{"network":{"mode":"off"},"console":{"mode":"buffer"}}}"""),
        )
        control.setOverrides("trigger", listOf(StreamOverride(signals = listOf("*"), untilMs = Long.MAX_VALUE)))
        assertEquals(Mode.STREAM, control.modeFor("console"))
        assertEquals(Mode.OFF, control.modeFor("network"))
        // Off by default stays off even for a capture.
        assertEquals(Mode.OFF, control.modeFor("longTasks"))
        control.setOverrides(
            "capture:c1",
            listOf(StreamOverride(signals = listOf("*"), untilMs = Long.MAX_VALUE, includeOff = true)),
        )
        assertEquals(Mode.STREAM, control.modeFor("network"))
        assertEquals(Mode.OFF, control.modeFor("longTasks"))
        control.setOverrides("capture:c1", emptyList())
        assertEquals(Mode.OFF, control.modeFor("network"))
        // Naming a signal turns it on.
        control.setOverrides("x", listOf(StreamOverride(signals = listOf("network"), untilMs = Long.MAX_VALUE)))
        assertEquals(Mode.STREAM, control.modeFor("network"))
        assertTrue(control.canExport("network"))
    }

    @Test
    fun `an override never goes past the app's ceiling`() {
        val control = ControlState(defaultSettings(), ceilingsFrom(captureLogs = false))
        control.addOverride(StreamOverride(signals = listOf("console", "*"), untilMs = Long.MAX_VALUE, includeOff = true))
        assertEquals(Mode.OFF, control.modeFor("console"))
        assertFalse(control.canExport("console"))
    }

    @Test
    fun `kill switch turns everything off`() {
        val control = ControlState(defaultSettings(), ceilingsFrom(screenCapture = true))
        control.applyRemote(parseSettingsDocument("""{"enabled":false}"""))
        assertFalse(control.enabled)
        assertEquals(Mode.OFF, control.modeFor("errors"))
        assertFalse(control.canExport("errors"))
        assertEquals(Mode.OFF, control.replayMode)
        assertNull(control.metrics)
    }

    @Test
    fun `metrics are off by rule, kill switch or app option`() {
        assertNotNull(ControlState(defaultSettings(), ceilingsFrom()).metrics)
        assertNull(ControlState(defaultSettings(), ceilingsFrom(captureMetrics = false)).metrics)
        val control = ControlState(defaultSettings(), ceilingsFrom())
        control.applyRemote(parseSettingsDocument("""{"metrics":{"enabled":false}}"""))
        assertNull(control.metrics)
        control.applyRemote(parseSettingsDocument("""{"limits":{"metrics":"off"}}"""))
        assertNull(control.metrics)
        control.applyRemote(parseSettingsDocument("""{"metrics":{"sampleRate":0.5}}"""))
        assertEquals(0.5, control.metrics!!.sampleRate, 0.0)
    }

    @Test
    fun `listeners see every change and a failing one does not stop the others`() {
        val control = ControlState(defaultSettings(), ceilingsFrom())
        val seen = ArrayList<Boolean>()
        control.onChange { throw IllegalStateException("boom") }
        control.onChange { seen.add(it.enabled) }
        control.applyRemote(parseSettingsDocument("""{"enabled":false}"""))
        control.applyRemote(null)
        assertEquals(listOf(false, true), seen)
        assertEquals(1.0, control.sampleRate("console"), 0.0)
    }
}
