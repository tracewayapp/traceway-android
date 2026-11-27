package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.core.Mode
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.persistence.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal open class FakeReplaySource : ReplaySource {
    class Capture(val clipId: String, val since: Long?, val until: Long?)

    var running = false
    var clears = 0
    val captures = ArrayList<Capture>()

    /** No frames at all: every clip comes back empty. */
    var empty = false

    override val isRunning: Boolean get() = running
    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun clearBuffer() {
        clears++
    }

    override fun hasFrames(sinceMs: Long?, untilMs: Long?): Boolean =
        !empty && (sinceMs == null || untilMs == null || sinceMs < untilMs)

    override fun captureRecording(clipId: String, sinceMs: Long?, untilMs: Long?): SessionRecordingPayload? {
        synchronized(captures) { captures.add(Capture(clipId, sinceMs, untilMs)) }
        if (empty) return null
        val end = untilMs ?: 0L
        return SessionRecordingPayload(
            clipId = clipId,
            events = listOf(linkedMapOf("type" to 2, "data" to "AAECAw==", "timestamp" to end)),
            startedAtMs = sinceMs ?: (end - 10_000),
            endedAtMs = end,
        )
    }
}

class ReplayControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = FakeHttp()
    private var index = 0
    private var session: String? = "s1"

    private fun controller(
        scheduler: ManualScheduler = ManualScheduler(nowMs = Rfc3339.parse("2026-01-01T12:00:00Z")!!),
        segmentMs: Long = 8000,
        budget: CollectionBudget = CollectionBudget(),
    ) = ReplayController(
        transport = RecordingsTransport("https://h/api/recordings", "tok", http, scheduler),
        sessionId = { session },
        nextSegmentIndex = { index++ },
        scheduler = scheduler,
        segmentMs = segmentMs,
        budget = budget,
    )

    private fun segments() = http.requests.flatMap { segmentsOf(it) }

    @Test
    fun aCaptureFlushWithoutAnExceptionIsPartOfTheSession() {
        val c = controller()
        c.attach(FakeReplaySource())
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        assertTrue(c.flush(30_000, null))
        assertTrue(c.drainBlocking(1000))
        val segment = segments().single()
        assertEquals("s1", segment["sessionId"])
        assertEquals(0L, segment["segmentIndex"])
        assertFalse(segment.containsKey("exceptionId"))
    }

    @Test
    fun withoutASessionOrAnExceptionThereIsNothingToAttachTo() {
        session = null
        val source = FakeReplaySource()
        val c = controller()
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        assertFalse(c.flush(30_000, null))
        assertFalse(c.flush(30_000, ""))
        c.drainBlocking(1000)
        assertTrue(source.captures.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun theWindowIsTheSmallerOfTheLookbackAndTheBufferAge() {
        val scheduler = ManualScheduler()
        val source = FakeReplaySource()
        val c = controller(scheduler)
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 4000)
        assertTrue(c.flush(30_000, "e1"))
        c.drainBlocking(1000)
        val capture = source.captures.single()
        assertEquals(scheduler.now(), capture.until)
        assertEquals(4000L, scheduler.now() - capture.since!!)
        assertEquals("e1", segments().single()["exceptionId"])
    }

    @Test
    fun clipsOfOneSessionNeverOverlapAndEveryExceptionGetsItsWindow() {
        val scheduler = ManualScheduler()
        val source = FakeReplaySource()
        val c = controller(scheduler)
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        c.flush(30_000, "e1")
        val first = scheduler.now()
        scheduler.advance(5000)
        c.flush(30_000, "e2")
        c.drainBlocking(1000)
        val now = scheduler.now()
        val windows = source.captures.associate { it.clipId to (it.since to it.until) }
        assertEquals((now - 30_000) to now, windows["e2"])
        assertEquals(first to now, windows["s1_1"])
        assertEquals(listOf(0L, 1L), segments().filter { it["sessionId"] == "s1" }.map { it["segmentIndex"] })
        val clip = segments().single { it["exceptionId"] == "e2" }
        assertFalse(clip.containsKey("sessionId"))
    }

    @Test
    fun streamUploadsConsecutiveSegmentsThatStartWhereTheLastEnded() {
        val scheduler = ManualScheduler()
        val source = FakeReplaySource()
        val c = controller(scheduler)
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        // A capture: its lookback first, then streaming from there.
        c.flush(30_000, null)
        val flushedUntil = scheduler.now()
        c.apply(Mode.STREAM, maxAgeMs = 600_000)
        assertEquals(Mode.STREAM, c.mode)
        scheduler.advance(3 * 8000)
        scheduler.advance(2000)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        c.drainBlocking(1000)
        val windows = source.captures.map { it.since to it.until }
        assertEquals(5, windows.size)
        assertEquals(flushedUntil, windows[1].first)
        for (i in 1 until windows.size) assertEquals("segment $i", windows[i - 1].second, windows[i].first)
        val uploaded = segments()
        assertTrue(uploaded.all { it["sessionId"] == "s1" && !it.containsKey("exceptionId") })
        assertEquals(uploaded.indices.map { it.toLong() }, uploaded.map { it["segmentIndex"] })
        // Back to buffering: no timer is left.
        scheduler.advance(60_000)
        assertEquals(5, source.captures.size)
    }

    @Test
    fun anExceptionWhileStreamingGetsItsOwnClipOutsideTheSession() {
        val scheduler = ManualScheduler()
        val source = FakeReplaySource()
        val c = controller(scheduler)
        c.attach(source)
        c.apply(Mode.STREAM, maxAgeMs = 600_000)
        scheduler.nowMs += 3000
        assertTrue(c.flush(30_000, "e1"))
        c.drainBlocking(1000)
        val clip = segments().single()
        assertEquals("e1", clip["exceptionId"])
        assertFalse(clip.containsKey("sessionId"))
        assertEquals(30_000L, scheduler.now() - source.captures.single().since!!)
    }

    @Test
    fun aSlowEncoderMakesTheNextSegmentLongerNotAQueue() {
        val scheduler = ManualScheduler(ioInline = false)
        val source = FakeReplaySource()
        val c = controller(scheduler, segmentMs = 8000)
        c.attach(source)
        c.apply(Mode.STREAM, maxAgeMs = 600_000)
        scheduler.advance(5 * 8000)
        // One clip at a time: the timed cuts wait for the first one.
        scheduler.runIo()
        assertEquals(1, source.captures.size)
        scheduler.advance(8000)
        scheduler.runIo()
        val windows = source.captures.map { it.since!! to it.until!! }
        assertEquals(2, windows.size)
        assertEquals(windows[0].second, windows[1].first)
        assertTrue(windows[1].second - windows[1].first > 30_000)
    }

    @Test
    fun aSessionThatIsNotSampledForReplayKeepsBuffering() {
        val c = controller()
        c.apply(Mode.STREAM, maxAgeMs = 600_000, sessionSampled = false)
        assertEquals(Mode.BUFFER, c.mode)
    }

    @Test
    fun theLastStreamedSegmentBelongsToTheSessionThatEnded() {
        val scheduler = ManualScheduler()
        val c = controller(scheduler)
        c.attach(FakeReplaySource())
        c.apply(Mode.STREAM, maxAgeMs = 600_000)
        scheduler.nowMs += 4000
        session = null
        c.onSessionEnd("s1")
        c.drainBlocking(1000)
        assertEquals("s1", segments().single()["sessionId"])
    }

    @Test
    fun aSessionStopsUploadingOnceItsBudgetIsUsed() {
        val scheduler = ManualScheduler()
        val c = controller(scheduler, budget = CollectionBudget(maxBytes = 600))
        c.attach(FakeReplaySource())
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        for (i in 0 until 4) {
            c.flush(30_000, "e$i")
            scheduler.nowMs += 60_000
            c.drainBlocking(1000)
        }
        assertEquals(2, segments().size)
        val snapshot = c.budget.snapshot("s1")
        assertEquals(2L, snapshot["traceway.collection.events"])
        assertEquals(2L, snapshot["traceway.collection.dropped"])
        assertEquals(0L, c.budget.snapshot("s2")["traceway.collection.events"])
    }

    @Test
    fun offStopsTheSourceClearsFramesAndRefusesToFlush() {
        val source = FakeReplaySource()
        val c = controller()
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        assertTrue(source.running)
        c.apply(Mode.OFF, maxAgeMs = 600_000)
        assertFalse(source.running)
        assertTrue(source.clears > 0)
        assertFalse(c.flush(30_000, "e1"))
        c.drainBlocking(1000)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun clipsAreAssembledOnTheIoPool() {
        val scheduler = ManualScheduler(ioInline = false)
        val source = FakeReplaySource()
        val c = controller(scheduler)
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        assertTrue(c.flush(30_000, "e1"))
        assertTrue(source.captures.isEmpty())
        val drained = c.drain()
        assertFalse(drained.isDone)
        scheduler.runIo()
        assertTrue(drained.isDone)
        assertEquals("e1", segments().single()["exceptionId"])
    }

    @Test
    fun aCrashAssemblesTheClipAtOnceAndSpoolsIt() {
        val scheduler = ManualScheduler(ioInline = false)
        val spool = tmp.newFolder("recordings")
        val source = FakeReplaySource()
        val c = ReplayController(
            transport = RecordingsTransport("https://h/api/recordings", "tok", http, scheduler, spoolDirectory = spool),
            sessionId = { session },
            nextSegmentIndex = { index++ },
            scheduler = scheduler,
        )
        c.attach(source)
        c.apply(Mode.BUFFER, maxAgeMs = 600_000)
        assertTrue(c.flush(30_000, "e1", sync = true))
        assertEquals(1, source.captures.size)
        assertEquals(1, c.spool(waitForClipsMs = 100))
        assertEquals(1, spool.listFiles()!!.size)
        // The next start sends it.
        val next = RecordingsTransport("https://h/api/recordings", "tok", http, ManualScheduler(), spoolDirectory = spool)
        next.loadSpooled()
        assertEquals("e1", segments().single()["exceptionId"])
        assertTrue(spool.listFiles()!!.isEmpty())
    }

    @Test
    fun theBudgetKeepsCountingAcrossARestartOfTheSession() {
        val store = StateStore(null)
        val a = CollectionBudget(maxBytes = 1000, store = store)
        assertTrue(a.admit("s1", 600))
        val b = CollectionBudget(maxBytes = 1000, store = store)
        assertFalse(b.admit("s1", 600))
        assertTrue(b.hasUploaded("s1"))
        assertTrue(b.admit("s2", 600))
        assertFalse(b.hasUploaded("s3"))
    }
}
