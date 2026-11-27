package com.tracewayapp.traceway.replay

import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.internal.asJsonList
import com.tracewayapp.traceway.internal.asJsonObject
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.otel.SeenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

internal fun segmentsOf(request: SeenRequest): List<Map<String, Any?>> =
    Json.parse(request.plain.toString(Charsets.UTF_8)).asJsonObject()!!["segments"].asJsonList()!!
        .map { it.asJsonObject()!! }

class RecordingsTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val url = "https://h/api/recordings"
    private val scheduler = ManualScheduler()

    private fun segment(exceptionId: String? = "e1") = RecordingSegment(
        exceptionId = exceptionId,
        events = listOf(linkedMapOf("type" to 2, "data" to "AAAA", "timestamp" to 1L)),
        startedAtMs = Rfc3339.parse("2026-01-01T00:00:00Z"),
        endedAtMs = Rfc3339.parse("2026-01-01T00:00:10Z"),
    )

    @Test
    fun postsGzippedJsonWithTheBearerToken() {
        val http = FakeHttp()
        val transport = RecordingsTransport(url, "tok", http, scheduler)
        transport.send(listOf(segment()))
        assertTrue(transport.flushBlocking(1000))
        val seen = http.requests.single()
        assertEquals(url, seen.url)
        assertEquals("Bearer tok", seen.headers["Authorization"])
        assertEquals("gzip", seen.headers["Content-Encoding"])
        assertEquals("application/json", seen.headers["Content-Type"])
        val sent = segmentsOf(seen).single()
        assertEquals("e1", sent["exceptionId"])
        assertEquals("2026-01-01T00:00:00.000Z", sent["startedAt"])
        assertEquals("2026-01-01T00:00:10.000Z", sent["endedAt"])
        assertFalse(sent.containsKey("sessionId"))
        assertEquals(1, transport.sentSegments)
    }

    @Test
    fun retriesATransientFailureAndThenDelivers() {
        var calls = 0
        val http = FakeHttp { HttpResponse(if (++calls < 3) 503 else 200) }
        val transport = RecordingsTransport(url, "tok", http, scheduler, retryDelaysMs = listOf(1, 1, 1))
        transport.send(listOf(segment()))
        assertEquals(1, calls)
        scheduler.advance(1)
        scheduler.advance(1)
        assertEquals(3, calls)
        assertEquals(1, transport.sentSegments)
        assertEquals(0, transport.pending)
    }

    @Test
    fun honoursRetryAfter() {
        var calls = 0
        val http = FakeHttp { HttpResponse(if (++calls < 2) 429 else 200, mapOf("retry-after" to "7")) }
        val transport = RecordingsTransport(url, "tok", http, scheduler)
        transport.send(listOf(segment()))
        // A new segment waits for the retry, it does not jump the queue.
        transport.send(listOf(segment("e2")))
        assertEquals(1, calls)
        scheduler.advance(6999)
        assertEquals(1, calls)
        scheduler.advance(1)
        assertEquals(3, calls)
        assertEquals(2, transport.sentSegments)
    }

    @Test
    fun keepsAtMostMaxQueuedBytesWaitingDroppingTheOldest() {
        val io = ManualScheduler(ioInline = false)
        val http = FakeHttp()
        val transport = RecordingsTransport(url, "tok", http, io, maxQueuedBytes = 10)
        repeat(5) { transport.send(listOf(segment())) }
        assertEquals(2, transport.pending)
        assertEquals(3, transport.failedSegments)
        transport.flush()
        io.runIo()
        assertEquals(2, transport.sentSegments)
    }

    @Test
    fun givesUpAfterTheRetryBudgetAndAtOnceOnAPermanentError() {
        var calls = 0
        val failing = RecordingsTransport(url, "tok", FakeHttp { calls++; HttpResponse(500) }, scheduler, retryDelaysMs = listOf(1, 1, 1))
        failing.send(listOf(segment()))
        repeat(5) { scheduler.advance(1) }
        assertEquals(4, calls)
        assertEquals(1, failing.failedSegments)
        assertEquals(0, failing.pending)

        calls = 0
        val rejected = RecordingsTransport(url, "tok", FakeHttp { calls++; HttpResponse(413) }, scheduler)
        rejected.send(listOf(segment()))
        rejected.flushBlocking(1000)
        assertEquals(1, calls)
        assertEquals(1, rejected.failedSegments)
    }

    @Test
    fun discardDropsWhatWaits() {
        val http = FakeHttp { HttpResponse(503) }
        val transport = RecordingsTransport(url, "tok", http, scheduler)
        transport.send(listOf(segment()))
        assertEquals(1, transport.pending)
        transport.discard()
        assertEquals(0, transport.pending)
        scheduler.advance(10_000)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aSpooledQueueIsSentByTheNextStart() {
        val spool = tmp.newFolder("recordings")
        val down = FakeHttp { HttpResponse(0) }
        val crashed = RecordingsTransport(url, "tok", down, scheduler, spoolDirectory = spool)
        crashed.send(listOf(segment("e1")))
        crashed.send(listOf(segment("e2"), segment("e3")))
        assertEquals(2, crashed.spool())
        // Spooling twice writes nothing new.
        assertEquals(0, crashed.spool())
        assertEquals(2, spool.listFiles()!!.size)

        val http = FakeHttp()
        val next = RecordingsTransport(url, "tok", http, scheduler, spoolDirectory = spool)
        next.loadSpooled()
        next.loadSpooled()
        assertTrue(next.flushBlocking(1000))
        assertEquals(listOf(listOf("e1"), listOf("e2", "e3")), http.requests.map { r -> segmentsOf(r).map { it["exceptionId"] } })
        assertEquals(3, next.sentSegments)
        assertTrue(spool.listFiles()!!.isEmpty())
    }

    @Test
    fun aSpooledBatchDeliveredInTheSameRunLeavesTheSpool() {
        val spool = tmp.newFolder("recordings")
        var status = 503
        val http = FakeHttp { HttpResponse(status) }
        val transport = RecordingsTransport(url, "tok", http, scheduler, spoolDirectory = spool)
        transport.send(listOf(segment()))
        assertEquals(1, transport.spool())
        status = 200
        transport.flushBlocking(1000)
        assertEquals(1, transport.sentSegments)
        assertTrue(spool.listFiles()!!.isEmpty())
    }

    @Test
    fun oldAndHalfWrittenSpoolFilesAreRemoved() {
        val spool = tmp.newFolder("recordings")
        val old = File(spool, "${scheduler.now() - RecordingsTransport.SPOOL_MAX_AGE_MS - 1}-0-1.json.gz").apply { writeBytes(byteArrayOf(1)) }
        val partial = File(spool, "${scheduler.now()}-1-1.json.gz.tmp").apply { writeBytes(byteArrayOf(1)) }
        val odd = File(spool, "garbage.json.gz").apply { writeBytes(byteArrayOf(1)) }
        val http = FakeHttp()
        RecordingsTransport(url, "tok", http, scheduler, spoolDirectory = spool).loadSpooled()
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertFalse(odd.exists())
        assertTrue(http.requests.isEmpty())
    }
}
