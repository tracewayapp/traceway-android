package com.tracewayapp.traceway.otel

import com.tracewayapp.traceway.core.OtlpDestination
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.SdkInfo
import com.tracewayapp.traceway.persistence.Outbox
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OtlpExportersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logsUrl = "https://collector.test/v1/logs"
    private val tracesUrl = "https://collector.test/v1/traces"
    private val logs = OtlpDestination(logsUrl, mapOf("X-Tenant" to "a"), external = true)
    private val traces = OtlpDestination(tracesUrl, emptyMap(), external = true)

    private fun outbox(dir: File, http: FakeHttp, scheduler: ManualScheduler) =
        Outbox(dir, mapOf("logs" to logs, "traces" to traces), http, scheduler).also { it.claim() }

    private fun queued(dir: File): List<File> = dir.listFiles()!!.filter { it.name.endsWith(".pb") }

    @Test
    fun postOtlpSendsGzippedProtobufWithTheExporterName() {
        val http = FakeHttp()
        val body = OtlpEncoding.logsRequest(listOf(TestLog("hello")))
        assertEquals(200, postOtlp(http, logs, body))
        val request = http.requests.single()
        assertEquals(logsUrl, request.url)
        assertEquals("application/x-protobuf", request.headers["Content-Type"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("a", request.headers["X-Tenant"])
        assertEquals(SdkInfo.OTLP_USER_AGENT, request.headers["User-Agent"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("OTel-OTLP-Exporter-Java/"))
        assertEquals(listOf("hello"), Otlp.logs(request.plain).map { it.body })
    }

    @Test
    fun postOtlpRetriesOnlyWhatMayPass() {
        val http = FakeHttp.sequence(503, 429)
        assertEquals(200, postOtlp(http, logs, byteArrayOf(1), listOf(0L, 0L)))
        assertEquals(3, http.requests.size)

        val rejected = FakeHttp.sequence(400)
        assertEquals(400, postOtlp(rejected, logs, byteArrayOf(1), listOf(0L, 0L)))
        assertEquals(1, rejected.requests.size)
    }

    @Test
    fun aPassingFailureIsRetriedBeforeTheBatchIsQueued() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp.sequence(503, 429)
        val exporter = OtlpLogExporter(logs, outbox(dir, http, scheduler), http, scheduler)
        val result = exporter.export(listOf(TestLog("retried")))
        assertEquals(1, http.requests.size)
        scheduler.advance(200)
        assertEquals(2, http.requests.size)
        scheduler.advance(1000)
        assertEquals(3, http.requests.size)
        assertTrue(result.isDone && result.isSuccess)
        assertTrue(queued(dir).isEmpty())
    }

    @Test
    fun aBatchThatKeepsFailingGoesToTheOutbox() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp { HttpResponse(503) }
        val exporter = OtlpLogExporter(logs, outbox(dir, http, scheduler), http, scheduler)
        val result = exporter.export(listOf(TestLog("queued")))
        scheduler.advance(1200)
        assertEquals(3, http.requests.size)
        assertTrue(result.isSuccess)
        val file = queued(dir).single()
        assertTrue(file.name.endsWith(".logs.pb"))
        assertEquals(listOf("queued"), Otlp.logs(file.readBytes()).map { it.body })
    }

    @Test
    fun aRejectedBatchIsDroppedNotQueued() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp { HttpResponse(400) }
        val exporter = OtlpLogExporter(logs, outbox(dir, http, scheduler), http, scheduler)
        val result = exporter.export(listOf(TestLog("rejected")))
        scheduler.advance(5000)
        assertEquals(1, http.requests.size)
        assertTrue(result.isDone)
        assertFalse(result.isSuccess)
        assertTrue(queued(dir).isEmpty())
    }

    @Test
    fun flushWaitsForAnExportAlreadyOnItsWay() {
        val scheduler = ManualScheduler(ioInline = false)
        val http = FakeHttp()
        val exporter = OtlpLogExporter(logs, null, http, scheduler)
        exporter.export(listOf(TestLog("in flight")))
        val flushed = exporter.flush()
        assertFalse(flushed.isDone)
        scheduler.runIo()
        assertTrue(flushed.isDone)
        assertEquals(listOf("in flight"), Otlp.logs(http.requests.single().plain).map { it.body })
        assertTrue(exporter.flush().isDone)
    }

    @Test
    fun flushWaitsThroughRetryDelays() {
        val scheduler = ManualScheduler()
        val http = FakeHttp.sequence(503)
        val exporter = OtlpLogExporter(logs, null, http, scheduler)
        exporter.export(listOf(TestLog("slow")))
        val flushed = exporter.flush()
        assertFalse(flushed.isDone)
        scheduler.advance(200)
        assertTrue(flushed.isDone)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun spansAreSentAsTraceRequests() {
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val exporter = OtlpSpanExporter(traces, null, http, scheduler)
        assertTrue(exporter.export(listOf(testSpan("GET /a"), testSpan("GET /b"))).isSuccess)
        val request = http.requests.single()
        assertEquals(tracesUrl, request.url)
        val spans = Otlp.spans(request.plain)
        assertEquals(listOf("GET /a", "GET /b"), spans.map { it.name })
        assertEquals("test-app", spans.first().resource["service.name"])
    }

    @Test
    fun persistOnlyWritesToTheOutboxBeforeReturning() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler(ioInline = false)
        val http = FakeHttp()
        val exporter = OtlpLogExporter(logs, outbox(dir, http, scheduler), http, scheduler)
        exporter.persistOnly = true
        val result = exporter.export(listOf(TestLog("crumb")))
        assertTrue(result.isDone && result.isSuccess)
        assertTrue(http.requests.isEmpty())
        assertEquals(listOf("crumb"), Otlp.logs(queued(dir).single().readBytes()).map { it.body })
    }

    @Test
    fun switchingToPersistOnlyQueuesBatchesWaitingForARetry() {
        val dir = tmp.newFolder("outbox")
        val scheduler = ManualScheduler()
        val http = FakeHttp { HttpResponse(0) }
        val exporter = OtlpLogExporter(logs, outbox(dir, http, scheduler), http, scheduler)
        val result = exporter.export(listOf(TestLog("waiting")))
        assertFalse(result.isDone)
        exporter.persistOnly = true
        assertTrue(result.isDone && result.isSuccess)
        assertEquals(1, queued(dir).size)
        scheduler.advance(2000)
        assertEquals(1, http.requests.size)
        assertTrue(exporter.flush().isDone)
    }

    @Test
    fun routingSendsIssuesAndControlRecordsToTraceway() {
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val tracewayUrl = "https://traceway.test/api/otel/v1/logs"
        val routing = RoutingLogExporter(
            OtlpLogExporter(logs, null, http, scheduler),
            OtlpLogExporter(OtlpDestination(tracewayUrl, mapOf("Authorization" to "Bearer t")), null, http, scheduler),
        )
        val records: List<LogRecordData> = listOf(
            TestLog("boom", Events.EXCEPTION),
            TestLog("line"),
            TestLog("start", Events.SESSION_START),
            TestLog("custom", "checkout_failed"),
            TestLog("note", Events.MESSAGE),
        )
        assertTrue(routing.export(records).isSuccess)
        assertEquals(
            listOf(Events.EXCEPTION, Events.SESSION_START, Events.MESSAGE),
            http.to(tracewayUrl).flatMap { Otlp.logs(it.plain) }.map { it.eventName },
        )
        assertEquals(listOf("line", "custom"), http.to(logsUrl).flatMap { Otlp.logs(it.plain) }.map { it.body })
        assertEquals("Bearer t", http.to(tracewayUrl).single().headers["Authorization"])
        assertEquals(null, http.to(logsUrl).single().headers["Authorization"])
    }

    @Test
    fun routingForwardsPersistOnlyAndSurvivesAThrowingExporter() {
        val scheduler = ManualScheduler()
        val http = FakeHttp()
        val throwing = object : LogRecordExporter {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode = error("broken")
            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        val own = OtlpLogExporter(logs, null, http, scheduler)
        val routing = RoutingLogExporter(throwing, own)
        routing.persistOnly = true
        assertTrue(own.persistOnly)
        routing.persistOnly = false
        val result = routing.export(listOf(TestLog("x"), TestLog("e", Events.EXCEPTION)))
        assertTrue(result.isDone)
        assertFalse(result.isSuccess)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun containersSplitAndMergeWithoutLosingRecords() {
        val a = OtlpEncoding.logsRequest(listOf(TestLog("a")))
        val b = OtlpEncoding.logsRequest(listOf(TestLog("b")))
        val ca = OtlpEncoding.containers(a).single()
        val cb = OtlpEncoding.containers(b).single()
        val merged = OtlpEncoding.request(listOf(ca.prefix to listOf(ca.scopes, cb.scopes)))
        assertEquals(1, Otlp.resourceCount(merged))
        val decoded = Otlp.logs(merged)
        assertEquals(listOf("a", "b"), decoded.map { it.body })
        assertEquals("1.0", decoded.last().resource["service.version"])
        val bare = OtlpEncoding.containers(OtlpEncoding.logsRequest(listOf(OtlpEncoding.withoutResource(TestLog("c", "evt"))))).single()
        val logs = Otlp.logs(OtlpEncoding.request(listOf(ca.prefix to listOf(bare.scopes))))
        assertEquals("evt", logs.single().eventName)
        assertEquals("test-app", logs.single().resource["service.name"])
    }
}
