package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.BufferEntry
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.otel.Events
import com.tracewayapp.traceway.otel.Otlp
import com.tracewayapp.traceway.otel.TEST_RESOURCE
import com.tracewayapp.traceway.otel.TelemetryItem
import com.tracewayapp.traceway.otel.TestLog
import com.tracewayapp.traceway.otel.testSpan
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BufferPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = ManualScheduler()
    private val all: (String) -> Boolean = { true }

    private fun log(body: String, timeMs: Long, signal: String = "console", event: String? = null, resource: Resource = TEST_RESOURCE) =
        BufferEntry<TelemetryItem>(timeMs, signal, 200, TelemetryItem.Log(TestLog(body, event, timeMs, res = resource)))

    private fun span(name: String, timeMs: Long) =
        BufferEntry<TelemetryItem>(timeMs, "network", 200, TelemetryItem.Span(testSpan(name, timeMs)))

    private fun persistence(dir: File, destination: String = "d1") = BufferPersistence(dir, scheduler, destination)

    private val now get() = scheduler.now()

    @Test
    fun theBufferSurvivesARestartWithinTheSession() {
        val dir = tmp.newFolder("state")
        val t = now
        persistence(dir).save("s1", listOf(log("first", t - 2000), span("GET /a", t - 1000), log("second", t - 500)))
        assertTrue(File(dir, BufferPersistence.FILE_NAME).exists())

        val next = persistence(dir)
        next.load("s1", maxAgeMs = 600_000, maxBytes = 2_000_000)
        assertEquals(3, next.restoredCount)
        // Loading removes the file: the next save writes what is still held.
        assertFalse(File(dir, BufferPersistence.FILE_NAME).exists())

        val bodies = next.take(t - 10_000, null, t, all)
        val logs = Otlp.logs(bodies.logs.single())
        assertEquals(listOf("first", "second"), logs.map { it.body })
        assertEquals("test-app", logs.first().resource["service.name"])
        assertEquals(1, Otlp.resourceCount(bodies.logs.single()))
        assertEquals(listOf("GET /a"), Otlp.spans(bodies.traces.single()).map { it.name })
        assertEquals(0, next.restoredCount)
    }

    @Test
    fun aRestoredBufferBelongsToItsSessionAndDestinationOnly() {
        val dir = tmp.newFolder("state")
        persistence(dir).save("s1", listOf(log("crumb", now)))
        val otherSession = persistence(dir)
        otherSession.load("s2", 600_000, 2_000_000)
        assertEquals(0, otherSession.restoredCount)

        persistence(dir).save("s1", listOf(log("crumb", now)))
        val otherDestination = persistence(dir, destination = "d2")
        otherDestination.load("s1", 600_000, 2_000_000)
        assertEquals(0, otherDestination.restoredCount)
    }

    @Test
    fun oldRecordsAndRecordsOverTheSizeAreDroppedNewestKept() {
        val dir = tmp.newFolder("state")
        val t = now
        persistence(dir).save("s1", (0 until 10).map { log("n$it", t - 10_000 + it * 1000) })
        val aged = persistence(dir)
        aged.load("s1", maxAgeMs = 5_000, maxBytes = 2_000_000)
        assertEquals((5 until 10).map { "n$it" }, Otlp.logs(aged.take(0, null, t, all).logs.single()).map { it.body })

        persistence(dir).save("s1", (0 until 10).map { log("n$it", t - 10_000 + it * 1000) })
        val sized = persistence(dir)
        val one = sized.also { it.load("s1", 600_000, 2_000_000) }.restoredEntries().first().bytes.size
        persistence(dir).save("s1", (0 until 10).map { log("n$it", t - 10_000 + it * 1000) })
        val small = persistence(dir)
        small.load("s1", maxAgeMs = 600_000, maxBytes = 3L * one)
        assertEquals(listOf("n7", "n8", "n9"), Otlp.logs(small.take(0, null, t, all).logs.single()).map { it.body })
    }

    @Test
    fun takeSelectsTheWindowAndSignalsAndDropsWhatMayNotLeave() {
        val dir = tmp.newFolder("state")
        val t = now
        persistence(dir).save(
            "s1",
            listOf(
                log("early", t - 60_000),
                log("console", t - 1000),
                log("nav", t - 900, signal = "navigation"),
                log("custom", t - 800, signal = "custom"),
                log("late", t + 10_000),
            ),
        )
        val p = persistence(dir).apply { load("s1", 600_000, 2_000_000) }
        val bodies = p.take(t - 5000, setOf("console", "navigation"), t, canExport = { it != "navigation" })
        assertEquals(listOf("console"), Otlp.logs(bodies.logs.single()).map { it.body })
        assertTrue(bodies.traces.isEmpty())
        // Outside the window or not selected: still held. Not exportable: gone.
        assertEquals(listOf("early", "custom", "late").size, p.restoredCount)
        assertTrue(p.take(t - 5000, setOf("navigation"), t, all).isEmpty)
    }

    @Test
    fun aLargeRestoredBufferIsSeveralRequests() {
        val dir = tmp.newFolder("state")
        val t = now
        persistence(dir).save("s1", (0 until 50).map { log("x".repeat(500) + it, t - 1000 + it) })
        val p = persistence(dir).apply { load("s1", 600_000, 10_000_000) }
        val bodies = p.take(0, null, t, all, maxBodyBytes = 5_000).logs
        assertTrue(bodies.size > 1)
        assertTrue(bodies.all { it.size < 6_000 })
        assertEquals((0 until 50).map { "x".repeat(500) + it }, bodies.flatMap { Otlp.logs(it) }.map { it.body })
    }

    @Test
    fun restoredRecordsKeepTheResourceTheyWereRecordedWith() {
        val dir = tmp.newFolder("state")
        val t = now
        val old = Resource.create(Attributes.of(AttributeKey.stringKey("service.version"), "0.9"))
        persistence(dir).save("s1", listOf(log("before update", t - 2000, resource = old)))
        val second = persistence(dir).apply { load("s1", 600_000, 2_000_000) }
        // The next run saves its own records next to the restored ones.
        second.save("s1", listOf(log("after update", t - 1000)))
        val third = persistence(dir).apply { load("s1", 600_000, 2_000_000) }
        val body = third.take(0, null, t, all).logs.single()
        assertEquals(2, Otlp.resourceCount(body))
        val logs = Otlp.logs(body).associateBy { it.body }
        assertEquals("0.9", logs.getValue("before update").resource["service.version"])
        assertEquals("1.0", logs.getValue("after update").resource["service.version"])
    }

    @Test
    fun tracewayLogsCanBeSplitFromTheRest() {
        val dir = tmp.newFolder("state")
        val t = now
        persistence(dir).save(
            "s1",
            listOf(
                log("boom", t - 300, signal = "errors", event = Events.EXCEPTION),
                log("line", t - 200),
                log("start", t - 100, signal = Events.SIGNAL_CONTROL, event = Events.SESSION_START),
            ),
        )
        val p = persistence(dir).apply { load("s1", 600_000, 2_000_000) }
        val bodies = p.take(0, null, t, all, splitTracewayLogs = true)
        assertEquals(listOf(Events.EXCEPTION, Events.SESSION_START), Otlp.logs(bodies.tracewayLogs.single()).map { it.eventName })
        assertEquals(listOf("line"), Otlp.logs(bodies.logs.single()).map { it.body })
    }

    @Test
    fun laterSavesWinAndAnEmptyBufferRemovesTheFile() {
        val dir = tmp.newFolder("state")
        val io = ManualScheduler(ioInline = false)
        val p = BufferPersistence(dir, io, "d1")
        p.save("s1", listOf(log("old", io.now())))
        p.saveNow("s1", listOf(log("new", io.now())))
        io.runIo()
        val loaded = BufferPersistence(dir, scheduler, "d1").apply { load("s1", 600_000, 2_000_000) }
        assertEquals(listOf("new"), Otlp.logs(loaded.take(0, null, Long.MAX_VALUE, all).logs.single()).map { it.body })

        p.saveNow("s1", listOf(log("x", io.now())))
        p.saveNow("s1", emptyList())
        assertFalse(File(dir, BufferPersistence.FILE_NAME).exists())
    }

    @Test
    fun aCorruptFileOnlyCostsItsRecords() {
        val dir = tmp.newFolder("state")
        File(dir, BufferPersistence.FILE_NAME).writeBytes(byteArrayOf(0x54, 0x57, 0x42, 0x31, 0, 9, 1))
        val p = persistence(dir)
        p.load("s1", 600_000, 2_000_000)
        assertEquals(0, p.restoredCount)
        assertFalse(File(dir, BufferPersistence.FILE_NAME).exists())
    }

    @Test
    fun retainAndClearRestored() {
        val dir = tmp.newFolder("state")
        persistence(dir).save("s1", listOf(log("a", now), log("b", now, signal = "custom")))
        val p = persistence(dir).apply { load("s1", 600_000, 2_000_000) }
        p.retain { it == "custom" }
        assertEquals(listOf("custom"), p.restoredEntries().map { it.signal })
        p.clearRestored()
        assertEquals(0, p.restoredCount)
    }
}
