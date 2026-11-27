package com.tracewayapp.traceway.persistence

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.internal.ExecutorScheduler
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.otel.Events
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.otel.Otlp
import com.tracewayapp.traceway.otel.OtlpEncoding
import com.tracewayapp.traceway.otel.TestLog
import com.tracewayapp.traceway.otel.testSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OutboxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logsUrl = "https://traceway.test/api/otel/v1/logs"
    private val tracesUrl = "https://traceway.test/api/otel/v1/traces"

    private fun destinations(token: String = "test-token") = mapOf(
        "logs" to OtlpDestination(logsUrl, mapOf("Authorization" to "Bearer $token")),
        "traces" to OtlpDestination(tracesUrl, mapOf("Authorization" to "Bearer $token")),
    )

    private fun queued(dir: File): List<File> = dir.listFiles()!!.filter { it.name.endsWith(".pb") }

    @Test
    fun aCapturedExceptionIsDurableBeforePersistLogReturnsAndIsSentOnce() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        assertTrue(outbox.persistLog(TestLog("last error", Events.EXCEPTION)))
        val saved = queued(dir).single()
        assertTrue(saved.name.startsWith("${outbox.destinationKey}-"))
        assertTrue(saved.name.endsWith(".logs.pb"))
        assertEquals(Events.EXCEPTION, Otlp.logs(saved.readBytes()).single().eventName)
        assertTrue(http.requests.isEmpty())

        assertTrue(outbox.drainBlocking(1000))
        outbox.drain()
        val sent = http.requests.single()
        assertEquals(logsUrl, sent.url)
        assertEquals("Bearer test-token", sent.headers["Authorization"])
        assertEquals("last error", Otlp.logs(sent.plain).single().body)
        assertFalse(saved.exists())
    }

    @Test
    fun addSchedulesADrain() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        outbox.add("traces", OtlpEncoding.tracesRequest(listOf(testSpan("GET /x"))))
        scheduler.advance(4999)
        assertTrue(http.requests.isEmpty())
        scheduler.advance(1)
        assertEquals(tracesUrl, http.requests.single().url)
        assertEquals(listOf("GET /x"), Otlp.spans(http.requests.single().plain).map { it.name })
        assertEquals(0, outbox.size)
    }

    @Test
    fun aTransientFailureKeepsTheBatchAndBacksOff() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp { HttpResponse(503) }
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        outbox.add("logs", OtlpEncoding.logsRequest(listOf(TestLog("one"))))
        outbox.add("logs", OtlpEncoding.logsRequest(listOf(TestLog("two"))))
        outbox.drain()
        // Stops at the first failure: the server is not reachable.
        assertEquals(1, http.requests.size)
        assertEquals(2, outbox.size)
        http.handler = { HttpResponse(200) }
        // The drain timer the first add set (5 s) tries again.
        scheduler.advance(5000)
        assertEquals(listOf("one", "two"), http.requests.drop(1).map { Otlp.logs(it.plain).single().body })
        assertEquals(0, outbox.size)
    }

    @Test
    fun aRejectedBatchIsDeleted() {
        val dir = tmp.newFolder("outbox")
        val http = FakeHttp { HttpResponse(400) }
        val outbox = Outbox(dir, destinations(), http, ManualScheduler()).apply { claim() }
        outbox.add("logs", byteArrayOf(1, 2, 3))
        outbox.drain()
        assertEquals(1, http.requests.size)
        assertEquals(0, outbox.size)
    }

    @Test
    fun claimRemovesHalfWrittenAndForeignBatches() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val outbox = Outbox(dir, destinations(), FakeHttp(), scheduler)
        val partial = File(dir, "${outbox.destinationKey}-1-0.logs.pb.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unmarked = File(dir, "1-0.logs.pb").apply { writeBytes(byteArrayOf(1)) }
        val own = File(dir, "${outbox.destinationKey}-${scheduler.now()}-0.logs.pb").apply { writeBytes(byteArrayOf(1)) }
        outbox.claim()
        outbox.dispose()
        assertFalse(partial.exists())
        assertFalse(unmarked.exists())
        assertTrue(own.exists())
        assertEquals(outbox.destinationKey, File(dir, "destination").readText())
    }

    @Test
    fun aQueueForAnotherProjectsTokenIsDroppedNotSent() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val other = Outbox(dir, destinations("other-token"), FakeHttp(), scheduler).apply { claim() }
        other.add("logs", OtlpEncoding.logsRequest(listOf(TestLog("other project"))))
        other.dispose()
        assertEquals(1, queued(dir).size)

        val http = FakeHttp()
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        assertTrue(outbox.destinationKey != other.destinationKey)
        outbox.drain()
        assertTrue(http.requests.isEmpty())
        assertTrue(queued(dir).isEmpty())
    }

    @Test
    fun oldAndExcessBatchesArePruned() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val outbox = Outbox(dir, destinations(), FakeHttp(), scheduler).apply { claim() }
        outbox.add("logs", byteArrayOf(1))
        scheduler.nowMs += Outbox.MAX_AGE_MS + 1
        outbox.add("logs", byteArrayOf(2))
        assertEquals(1, outbox.size)
        for (i in 0 until Outbox.MAX_BATCHES + 5) outbox.add("logs", byteArrayOf(3))
        assertEquals(Outbox.MAX_BATCHES, outbox.size)
        assertFalse(outbox.add("logs", ByteArray(Outbox.MAX_BATCH_BYTES + 1)))
        assertFalse(outbox.add("metrics", byteArrayOf(1)))
        assertFalse(outbox.add("logs", ByteArray(0)))
    }

    @Test
    fun batchesAreSentOldestFirst() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        for (i in 0 until 12) outbox.add("logs", OtlpEncoding.logsRequest(listOf(TestLog("n$i"))))
        outbox.drain()
        assertEquals((0 until 12).map { "n$it" }, http.requests.map { Otlp.logs(it.plain).single().body })
    }

    @Test
    fun withoutADirectoryOrClaimNothingIsKept() {
        val noDir = Outbox(null, destinations(), FakeHttp(), ManualScheduler())
        noDir.claim()
        assertFalse(noDir.add("logs", byteArrayOf(1)))
        assertFalse(noDir.persistLog(TestLog("x")))
        val unclaimed = Outbox(tmp.newFolder("outbox"), destinations(), FakeHttp(), ManualScheduler())
        assertFalse(unclaimed.add("logs", byteArrayOf(1)))
        assertTrue(noDir.drainBlocking(10))
    }

    @Test
    fun disposeStopsTheDrainTimer() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
        outbox.add("logs", byteArrayOf(1))
        outbox.dispose()
        scheduler.advance(10_000)
        assertTrue(http.requests.isEmpty())
        assertFalse(outbox.add("logs", byteArrayOf(1)))
        assertEquals(1, queued(dir).size)
    }

    @Test
    fun drainBlockingWaitsOnRealThreadsAndTimesOut() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ExecutorScheduler()
        try {
            val release = CountDownLatch(1)
            val http = FakeHttp {
                release.await(5, TimeUnit.SECONDS)
                HttpResponse(200)
            }
            val outbox = Outbox(dir, destinations(), http, scheduler).apply { claim() }
            outbox.add("logs", byteArrayOf(1))
            assertFalse(outbox.drainBlocking(50))
            release.countDown()
            assertTrue(outbox.drainBlocking(5000))
            assertEquals(0, outbox.size)
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun drainBlockingIsFalseWhileIoHasNotRun() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler(ioInline = false)
        val outbox = Outbox(dir, destinations(), FakeHttp(), scheduler).apply { claim() }
        outbox.add("logs", byteArrayOf(1))
        assertFalse(outbox.drainBlocking(10))
        scheduler.runIo()
        assertEquals(0, outbox.size)
    }

    @Test
    fun queuesOfReceiversNoLongerConfiguredAreRemoved() {
        val state = tmp.newFolder("state")
        val stale = File(state, "outbox-0badc0de").apply { mkdirs() }
        File(stale, "1-0.logs.pb").writeBytes(byteArrayOf(1))
        val kept = File(state, "outbox-keep").apply { mkdirs() }
        val traceway = File(state, "outbox").apply { mkdirs() }
        Outbox.pruneStale(state, keep = "outbox-keep")
        assertFalse(stale.exists())
        assertTrue(kept.exists())
        assertTrue(traceway.exists())
    }
}
