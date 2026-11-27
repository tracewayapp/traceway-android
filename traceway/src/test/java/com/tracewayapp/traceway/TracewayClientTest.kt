package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.ExecutorScheduler
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.gunzip
import com.tracewayapp.traceway.opamp.AgentConfigObject
import com.tracewayapp.traceway.opamp.RemoteConfig
import com.tracewayapp.traceway.opamp.encodeServerToAgent
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.otel.Otlp
import com.tracewayapp.traceway.otel.SeenRequest
import com.tracewayapp.traceway.replay.FakeReplaySource
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

private const val CONNECTION = "tok@https://traceway.test"
private const val OPAMP_URL = "https://traceway.test/api/otel/v1/opamp"
private const val RECORDINGS_URL = "https://traceway.test/api/recordings"
private const val LOGS_URL = "https://traceway.test/api/otel/v1/logs"

/** Collects exported logs and survives shutdown, unlike the SDK's in-memory exporter, so a test can restart the client. */
private class KeptLogs : LogRecordExporter {
    private val items = ArrayList<LogRecordData>()
    val finishedLogRecordItems: List<LogRecordData> get() = synchronized(items) { ArrayList(items) }
    fun reset() = synchronized(items) { items.clear() }
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        synchronized(items) { items.addAll(logs) }
        return CompletableResultCode.ofSuccess()
    }
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private class KeptSpans : SpanExporter {
    private val items = ArrayList<SpanData>()
    val finishedSpanItems: List<SpanData> get() = synchronized(items) { ArrayList(items) }
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        synchronized(items) { items.addAll(spans) }
        return CompletableResultCode.ofSuccess()
    }
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/** The client end to end: OpenTelemetry records in memory, a fake Traceway server, a manual clock. */
class TracewayClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scheduler = ManualScheduler(ioInline = true)
    private val logs = KeptLogs()
    private val spans = KeptSpans()

    /** The settings document the fake OpAMP server serves, or null for none. */
    private var remoteDocument: String? = null
    private var opampStatus = 200
    private val http = FakeHttp { request -> serve(request) }

    private fun serve(request: SeenRequest): HttpResponse = when (request.url) {
        OPAMP_URL -> {
            if (opampStatus != 200) {
                HttpResponse(opampStatus)
            } else {
                val doc = remoteDocument
                val config = doc?.let {
                    RemoteConfig().apply {
                        configMap["traceway"] = AgentConfigObject(it.toByteArray(), "application/json")
                        configHash = MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                    }
                }
                HttpResponse(200, body = encodeServerToAgent(remoteConfig = config, heartbeatIntervalSeconds = 300))
            }
        }
        else -> HttpResponse(200)
    }

    private fun start(
        options: TracewayOptions = TracewayOptions(),
        stateDir: File? = null,
        inMemory: Boolean = true,
        connectionString: String? = CONNECTION,
    ): TracewayClient = TracewayClient.initializeForTest(
        connectionString,
        options,
        TracewayTestOverrides(
            logExporter = if (inMemory) logs else null,
            spanExporter = if (inMemory) spans else null,
            http = http,
            scheduler = scheduler,
            stateDirectory = stateDir,
            random = { 0.5 },
        ),
    )

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
    }

    private fun exported(): List<LogRecordData> = logs.finishedLogRecordItems
    private fun named(name: String?): List<LogRecordData> = exported().filter { it.eventName == name }
    private fun LogRecordData.attr(key: String): Any? {
        var found: Any? = null
        attributes.forEach { k, v -> if (k.key == key) found = v }
        return found
    }

    private fun exportedSpans(): List<SpanData> = spans.finishedSpanItems

    // ---------------------------------------------------------------- ring buffer

    @Test
    fun breadcrumbsStayOnTheDeviceUntilAnExceptionFlushesThem() {
        val client = start()
        client.log("opening checkout")
        client.recordAction("cart", "add_item", mapOf("sku" to "A1"))
        client.recordNavigation("/checkout")
        client.flush(2000)
        assertTrue(named(null).isEmpty())
        assertTrue(named("traceway.action").isEmpty())
        assertEquals(3, client.bufferedCount)

        client.captureException(IllegalStateException("boom"))
        client.flush(2000)
        assertEquals(1, named("exception").size)
        assertEquals("opening checkout", named(null).single().bodyValue?.asString())
        assertEquals(1, named("traceway.action").size)
        assertEquals(1, named("browser.navigation").size)
        assertEquals(0, client.bufferedCount)
        assertEquals(1, named("traceway.trigger.fired").size)
    }

    @Test
    fun theExceptionRecordCarriesWhatTheServerNeeds() {
        val client = start()
        client.setAttribute("user.id", "u-1")
        client.captureException(IllegalArgumentException("bad input"))
        client.flush(2000)
        val record = named("exception").single()
        assertEquals("java.lang.IllegalArgumentException", record.attr("exception.type"))
        assertEquals("bad input", record.attr("exception.message"))
        val stack = record.attr("exception.stacktrace") as String
        assertTrue(stack, stack.startsWith("java.lang.IllegalArgumentException: bad input\n\tat "))
        assertNotNull(record.attr("traceway.exception.id"))
        assertEquals(client.currentSessionId, record.attr("session.id"))
        assertNull("only session events carry the start, as in the browser SDK", record.attr("traceway.session.started_at"))
        assertEquals("u-1", record.attr("user.id"))
        // No screen recording in this app: the clip status says why there is none.
        assertEquals("disabled", record.attr("traceway.replay.status"))
        val resource = record.resource.attributes
        assertEquals("traceway-android", resource.get(AttributeKey.stringKey("traceway.sdk.name")))
        assertEquals("unknown", resource.get(AttributeKey.stringKey("service.version")))
        assertEquals(client.getInstanceId(), resource.get(AttributeKey.stringKey("service.instance.id")))
        assertEquals("traceway-android", record.instrumentationScopeInfo.name)
    }

    @Test
    fun messagesAreSentAtOnceAndDoNotFlushTheBuffer() {
        val client = start()
        client.log("a breadcrumb")
        client.captureMessage("hello")
        client.flush(2000)
        assertEquals("hello", named("traceway.message").single().bodyValue?.asString())
        assertTrue(named(null).isEmpty())
        assertEquals(1, client.bufferedCount)
    }

    @Test
    fun repeatedIdenticalErrorsCollapseAndAreCounted() {
        val client = start()
        repeat(3) { client.captureException(IllegalStateException("same")) }
        scheduler.advance(1500)
        client.captureException(IllegalStateException("same"))
        client.flush(2000)
        val errors = named("exception")
        assertEquals(2, errors.size)
        assertEquals(2L, errors[1].attr("traceway.error.suppressed_count"))
    }

    @Test
    fun sampleRateZeroDropsErrorsAndTheirBreadcrumbs() {
        val client = start(TracewayOptions(sampleRate = 0.0))
        client.log("context")
        client.captureException(IllegalStateException("dropped"))
        client.flush(2000)
        assertTrue(named("exception").isEmpty())
        assertTrue(named(null).isEmpty())
        assertEquals(1, client.bufferedCount)
    }

    @Test
    fun captureFlagsAreCeilings() {
        val client = start(
            TracewayOptions(
                captureLogs = false,
                collection = mapOf("signals" to mapOf("console" to mapOf("mode" to "stream"), "custom" to mapOf("mode" to "stream"))),
            ),
        )
        client.log("never")
        client.track("checkout_failed")
        client.flush(2000)
        assertTrue(named(null).isEmpty())
        assertEquals(1, named("checkout_failed").size)
    }

    @Test
    fun recordAllSessionsStreamsBreadcrumbs() {
        val client = start(TracewayOptions(recordAllSessions = true))
        client.log("streamed")
        client.flush(2000)
        assertEquals(1, named(null).size)
        assertEquals(0, client.bufferedCount)
    }

    @Test
    fun localTriggersRunFromTheCollectionDocument() {
        val client = start(
            TracewayOptions(
                remoteConfig = false,
                collection = mapOf(
                    "triggers" to listOf(
                        mapOf(
                            "id" to "checkout-failed-twice",
                            "steps" to listOf(
                                mapOf(
                                    "match" to mapOf("key" to "event.name", "op" to "eq", "value" to "checkout_failed"),
                                    "count" to 2,
                                    "periodSeconds" to 60,
                                ),
                            ),
                            "actions" to listOf(mapOf("type" to "flush", "lookbackSeconds" to 120, "signals" to listOf("*"))),
                        ),
                    ),
                ),
            ),
        )
        client.log("before")
        client.track("checkout_failed")
        client.flush(2000)
        assertTrue(named(null).isEmpty())
        client.track("checkout_failed")
        client.flush(2000)
        assertEquals(1, named(null).size)
        assertEquals(2, named("checkout_failed").size)
        assertEquals("checkout-failed-twice", named("traceway.trigger.fired").single().attr("traceway.trigger.id"))
    }

    // ---------------------------------------------------------------- logs, screens, filters

    @Test
    fun logLinesCarryTheirLevelAndSourceButNoEventName() {
        val client = start(TracewayOptions(recordAllSessions = true))
        Traceway.log("payment declined", TracewayLogLevel.WARN, mapOf("order.id" to "o-1"))
        @Suppress("DEPRECATION")
        client.recordLog("the old way", "error")
        client.log("Traceway: the SDK's own line")
        client.flush(2000)
        val lines = named(null)
        assertEquals(listOf("payment declined", "the old way"), lines.map { it.bodyValue?.asString() })
        val first = lines[0]
        assertEquals(Severity.WARN, first.severity)
        assertEquals("WARN", first.severityText)
        assertEquals("api", first.attr("log.source"))
        assertEquals("o-1", first.attr("order.id"))
        assertEquals(Severity.ERROR, lines[1].severity)
        assertEquals("ERROR", lines[1].severityText)
    }

    @Test
    fun issuesAndNavigationsCarryTheScreenTheyHappenedOn() {
        val client = start()
        Traceway.recordNavigation("/cart")
        scheduler.advance(500)
        Traceway.recordNavigation("/orders/8841", "push", from = "/cart")
        Traceway.setRoute("orders/:id")
        scheduler.advance(1200)
        client.captureException(IllegalStateException("x"))
        client.captureMessage("hello", mapOf("app.screen.name" to "/the-callers"))
        Traceway.recordNavigation("/orders/8841", "replace")
        Traceway.recordNavigation("/home")
        client.captureException(IllegalStateException("y"))
        client.flush(2000)
        val exception = named("exception").first()
        assertEquals("/orders/8841", exception.attr("app.screen.name"))
        assertEquals("/orders/:id", exception.attr("app.screen.route"))
        assertEquals("/cart", exception.attr("app.screen.previous"))
        assertEquals(1200L, exception.attr("app.screen.duration_ms"))
        val message = named("traceway.message").single()
        assertEquals("the caller's attributes win", "/the-callers", message.attr("app.screen.name"))
        assertEquals("/orders/:id", message.attr("app.screen.route"))
        val navigations = named("browser.navigation")
        assertEquals("/cart", navigations[1].attr("navigation.from"))
        assertNull("set after the navigation", navigations[1].attr("app.screen.route"))
        assertEquals("/orders/:id", navigations[2].attr("app.screen.route"))
        assertNull("another screen has a route of its own", navigations[3].attr("app.screen.route"))
        assertNull(named("exception").last().attr("app.screen.route"))
        assertEquals("/orders/8841", named("exception").last().attr("app.screen.previous"))
    }

    @Test
    fun aNavigationCanNameItsRouteTemplate() {
        val client = start()
        Traceway.recordNavigation("/orders/8841", from = "/cart", route = "orders/{id}")
        client.captureException(IllegalStateException("x"))
        Traceway.recordNavigation("/home")
        client.captureException(IllegalStateException("y"))
        client.flush(2000)
        val navigations = named("browser.navigation")
        assertEquals("/orders/{id}", navigations[0].attr("app.screen.route"))
        assertEquals("/orders/{id}", named("exception").first().attr("app.screen.route"))
        assertNull("the next screen starts without one", navigations[1].attr("app.screen.route"))
    }

    @Test
    fun ignoredErrorsAndBeforeCaptureDropIssues() {
        val seen = ArrayList<TracewayCapturedError>()
        val client = start(TracewayOptions(beforeCapture = { seen.add(it); it.attributes["drop"] != "yes" }))
        client.captureException(java.net.UnknownHostException("Unable to resolve host \"api.example.com\""))
        client.captureException(RuntimeException("Request timeout after 30s"))
        client.captureException(RuntimeException("Request failed: 404"))
        client.captureException(IllegalStateException("kept"))
        client.captureException(IllegalStateException("dropped by the callback"), mapOf("drop" to "yes"))
        client.captureMessage("message dropped", mapOf("drop" to "yes"))
        client.captureMessage("message kept")
        client.flush(2000)
        assertEquals(listOf("kept"), named("exception").map { it.attr("exception.message") })
        assertEquals(listOf("message kept"), named("traceway.message").map { it.bodyValue?.asString() })
        // The callback sees only what the patterns let through.
        assertEquals(4, seen.size)
        val first = seen[0]
        assertEquals("java.lang.IllegalStateException", first.type)
        assertEquals("kept", first.message)
        assertFalse(first.isMessage)
        assertTrue(first.error is IllegalStateException)
        assertTrue(first.stackTrace!!.startsWith("java.lang.IllegalStateException: kept"))
        val message = seen.last()
        assertTrue(message.isMessage)
        assertNull(message.type)
        assertEquals("message kept", message.message)
        assertTrue(TracewayOptions.DEFAULT_IGNORE_ERRORS.contains("UnknownHostException"))
    }

    @Test
    fun customPatternsReplaceTheDefaultsAndAThrowingCallbackKeepsTheRecord() {
        val client = start(
            TracewayOptions(
                ignoreErrors = listOf("Flaky", Regex("^java\\.io\\.")),
                beforeCapture = { throw IllegalStateException("a bug in the callback") },
            ),
        )
        client.captureException(java.net.UnknownHostException("no network"))
        client.captureException(RuntimeException("Flaky backend"))
        client.captureException(java.io.IOException("disk full"))
        client.captureException(RuntimeException("line one\nFlaky only below"))
        client.flush(2000)
        assertEquals(
            listOf("no network", "line one\nFlaky only below"),
            named("exception").map { it.attr("exception.message") },
        )
    }

    @Test
    fun anInteractionTracesItsRequestsAndTheExceptionThatFailedIt() {
        val client = start()
        val checkout = Traceway.startInteraction("checkout", mapOf("cart.items" to 3))
        assertNotNull(checkout.traceId)
        val answer = checkout.run {
            val request = client.startRequest("POST", "https://api.example.com/orders")!!
            client.endRequest(request, 500, 10, 20, null)
            client.log("placing the order")
            42
        }
        assertEquals(42, answer)
        checkout.setAttribute("payment.method", "card")
        checkout.captureException(IllegalStateException("payment failed"))
        checkout.end()
        assertTrue(checkout.isEnded)
        client.flush(2000)

        val exception = named("exception").single()
        assertEquals(checkout.traceId, exception.spanContext.traceId)
        assertEquals(checkout.spanId, exception.spanContext.spanId)
        assertEquals(checkout.traceId, named(null).single().spanContext.traceId)
        val interaction = exportedSpans().single { it.name == "checkout" }
        assertEquals(true, interaction.attributes.get(AttributeKey.booleanKey("traceway.interaction")))
        assertEquals(3L, interaction.attributes.get(AttributeKey.longKey("cart.items")))
        assertEquals("card", interaction.attributes.get(AttributeKey.stringKey("payment.method")))
        assertEquals(StatusCode.ERROR, interaction.status.statusCode)
        val request = exportedSpans().single { it.name == "POST" }
        assertEquals(checkout.traceId, request.traceId)
        assertEquals(checkout.spanId, request.parentSpanId)

        // A request outside it starts a trace of its own; one that ends well stays with the breadcrumbs.
        val outside = client.startRequest("GET", "https://api.example.com/health")!!
        client.endRequest(outside, 200, 0, 2, null)
        val search = Traceway.startInteraction("search")
        search.end()
        search.end(failed = true)
        client.flush(2000)
        assertTrue(exportedSpans().none { it.name == "search" || it.name == "GET" })
    }

    @Test
    fun anInteractionBeforeTheSdkStartsRecordsNothingButRuns() {
        TracewayClient.resetForTest()
        val idle = Traceway.startInteraction("checkout")
        assertNull(idle.traceId)
        assertEquals("ran", idle.run { "ran" })
        idle.captureException(IllegalStateException("nowhere to go"))
        idle.end()
        assertTrue(idle.isEnded)
    }

    @Test
    fun theFacadeNamesTheSession() {
        val client = start()
        assertNotNull(Traceway.currentSessionId)
        assertEquals(client.currentSessionId, Traceway.currentSessionId)
    }

    @Test
    fun droppedDataIsReportedAsCollectionHealthLessAndLessOften() {
        val client = start()
        repeat(3) { client.captureException(IllegalStateException("same")) }
        client.flush(2000)
        assertTrue(named("traceway.collection.health").isEmpty())
        scheduler.advance(1000)
        client.flush(2000)
        val health = named("traceway.collection.health").single()
        assertEquals(2L, health.attr("traceway.error.suppressed_total"))
        assertNotNull(health.attr("traceway.collection.bytes"))
        assertNotNull(health.attr("traceway.replay.failed_segments"))
        assertEquals(client.currentSessionId, health.attr("session.id"))
        // The session's row learns it once.
        assertEquals(1, named("traceway.session.update").size)

        repeat(2) { client.captureException(IllegalStateException("again")) }
        scheduler.advance(1000)
        client.flush(2000)
        assertEquals("the next one waits twice as long", 1, named("traceway.collection.health").size)
        scheduler.advance(1000)
        client.flush(2000)
        assertEquals(2, named("traceway.collection.health").size)
        assertEquals(3L, named("traceway.collection.health").last().attr("traceway.error.suppressed_total"))
        assertEquals(1, named("traceway.session.update").size)
    }

    @Test
    fun sessionEventsCarryTheErrorsHeldBackSoFar() {
        val client = start()
        client.flush(2000)
        assertEquals(0L, named("session.start").single().attr("traceway.error.suppressed_total"))
        repeat(3) { client.captureException(IllegalStateException("same")) }
        scheduler.advance(1000)
        client.flush(2000)
        assertEquals(2L, named("traceway.session.update").single().attr("traceway.error.suppressed_total"))
        client.shutdown(2000)
        assertEquals(2L, named("session.end").single().attr("traceway.error.suppressed_total"))
    }

    @Test
    fun sessionEventsReportTheReplayImagePolicy() {
        start(
            TracewayOptions(
                screenCapture = true,
                replayImages = TracewayImagePolicy.WIFI,
                collection = mapOf("replay" to mapOf("images" to "always")),
            ),
        ).flush(2000)
        val session = named("session.start").single()
        assertEquals("the app's ceiling", "wifi", session.attr("traceway.replay.images"))
        assertEquals(0L, session.attr("traceway.replay.image_bytes"))

        TracewayClient.resetForTest()
        logs.reset()
        start(TracewayOptions(screenCapture = true)).flush(2000)
        assertEquals("the default setting", "wifi", named("session.start").single().attr("traceway.replay.images"))

        TracewayClient.resetForTest()
        logs.reset()
        start(TracewayOptions(collection = mapOf("replay" to mapOf("images" to "always")))).flush(2000)
        assertEquals("no screen capture, no pictures", "off", named("session.start").single().attr("traceway.replay.images"))
    }

    // ---------------------------------------------------------------- network

    @Test
    fun requestsAreClientSpansAndTheSdkOwnRequestsAreNot() {
        val client = start(TracewayOptions(propagateTraceHeaderUrls = listOf("https://api.example.com/")))
        assertNull(client.startRequest("POST", LOGS_URL))
        assertNull(client.startRequest("POST", RECORDINGS_URL))

        val propagated = client.startRequest("get", "https://api.example.com/users")!!
        val header = propagated.traceparent!!
        assertTrue(header, Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$").matches(header))
        scheduler.advance(120)
        client.endRequest(propagated, 503, 10, 20, null)

        val plain = client.startRequest("GET", "https://cdn.example.com/a.png")!!
        assertNull(plain.traceparent)
        client.endRequest(plain, null, null, null, "UnknownHostException")
        // Network is buffered by default: an error brings the spans up.
        client.captureException(RuntimeException("x"))
        client.flush(2000)

        val recorded = exportedSpans()
        assertEquals(2, recorded.size)
        val first = recorded.first { it.attributes.get(AttributeKey.stringKey("url.full")) == "https://api.example.com/users" }
        assertEquals("GET", first.name)
        assertEquals(header.split("-")[1], first.traceId)
        assertEquals(header.split("-")[2], first.spanId)
        assertEquals(503L, first.attributes.get(AttributeKey.longKey("http.response.status_code")))
        assertEquals("503", first.attributes.get(AttributeKey.stringKey("error.type")))
        assertEquals(client.currentSessionId, first.attributes.get(AttributeKey.stringKey("session.id")))
        assertEquals(120_000_000L, first.endEpochNanos - first.startEpochNanos)
        val failed = recorded.first { it != first }
        assertEquals("UnknownHostException", failed.attributes.get(AttributeKey.stringKey("error.type")))
    }

    // ---------------------------------------------------------------- replay

    @Test
    fun aClipIsUploadedOnlyForAnExceptionLinkedByItsId() {
        val client = start(TracewayOptions(screenCapture = true))
        val source = FakeReplaySource()
        client.setReplaySource(source)
        assertTrue(source.running)
        client.log("no clip for this")
        client.captureMessage("nor this")
        client.flush(2000)
        assertTrue(http.to(RECORDINGS_URL).isEmpty())

        client.captureException(IllegalStateException("with clip"))
        client.flush(2000)
        val exception = named("exception").single()
        assertEquals("requested", exception.attr("traceway.replay.status"))
        val upload = http.to(RECORDINGS_URL).single()
        @Suppress("UNCHECKED_CAST")
        val body = Json.parse(String(gunzip(upload.body))) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val segment = (body["segments"] as List<Map<String, Any?>>).single()
        assertEquals(exception.attr("traceway.exception.id"), segment["exceptionId"])
        assertEquals("Bearer tok", upload.headers["Authorization"])
    }

    @Test
    fun aSecondErrorInsideTheCooldownGetsBreadcrumbsButNoClip() {
        val client = start(TracewayOptions(screenCapture = true))
        client.setReplaySource(FakeReplaySource())
        client.captureException(IllegalStateException("one"))
        client.flush(2000)
        scheduler.advance(2000)
        client.log("between the errors")
        client.captureException(IllegalStateException("two"))
        client.flush(2000)
        assertEquals(1, http.to(RECORDINGS_URL).size)
        val second = named("exception").last()
        assertEquals("cooldown", second.attr("traceway.replay.status"))
        assertEquals(1, named(null).size)
    }

    // ---------------------------------------------------------------- remote config

    @Test
    fun remoteConfigIsAppliedReportedAndCachedForTheNextStart() {
        val dir = tmp.newFolder("state")
        remoteDocument = """{"schemaVersion":1,"signals":{"console":{"mode":"stream"}}}"""
        var client = start(stateDir = dir)
        assertTrue(http.to(OPAMP_URL).isNotEmpty())
        assertEquals("application/x-protobuf", http.to(OPAMP_URL).first().headers["Content-Type"])
        client.log("streams now")
        client.flush(2000)
        assertEquals(1, named(null).size)
        assertEquals(1, named("traceway.config.applied").size)

        // The next start applies the cached document before the server answers.
        client.shutdown(2000)
        remoteDocument = null
        opampStatus = 503
        logs.reset()
        client = start(stateDir = dir)
        client.log("still streams")
        client.flush(2000)
        assertEquals(1, named(null).size)
    }

    @Test
    fun anInvalidDocumentIsRejectedAndTheDefaultsStay() {
        remoteDocument = """{"schemaVersion":2}"""
        val client = start()
        client.log("buffered")
        client.flush(2000)
        assertTrue(named(null).isEmpty())
        assertTrue(named("traceway.config.applied").isEmpty())
        assertEquals(1, client.bufferedCount)
    }

    @Test
    fun aForbiddenAnswerStopsPollingAndLeavesTheDefaults() {
        opampStatus = 403
        val client = start()
        val polls = http.to(OPAMP_URL).size
        scheduler.advance(30 * 60_000L)
        assertEquals(polls, http.to(OPAMP_URL).size)
        client.captureException(RuntimeException("still reported"))
        client.flush(2000)
        assertEquals(1, named("exception").size)
    }

    @Test
    fun theKillSwitchStopsEverythingButThePoll() {
        remoteDocument = """{"enabled":false}"""
        val client = start()
        client.captureException(RuntimeException("not sent"))
        client.captureMessage("not sent")
        client.flush(2000)
        assertTrue(exported().none { it.eventName == "exception" || it.eventName == "traceway.message" })
    }

    @Test
    fun aDashboardCaptureUploadsItsLookbackAndThenStreams() {
        val until = com.tracewayapp.traceway.internal.Rfc3339.format(scheduler.now() + 60 * 60_000L)
        val client = start()
        client.log("before the capture")
        remoteDocument = """{"captures":[{"id":"cap-1","until":"$until","lookbackSeconds":600,"signals":["*"]}]}"""
        client.refreshConfig()
        client.flush(2000)
        assertEquals(1, named(null).size)
        assertEquals("cap-1", named("traceway.capture.started").single().attr("traceway.capture.id"))
        client.log("during the capture")
        client.flush(2000)
        assertEquals(2, named(null).size)
        assertEquals("[\"cap-1\"]", named(null).last().attr("traceway.capture.ids"))
    }

    @Test
    fun theAgentDescriptionNamesThisInstallationAndItsAttributes() {
        val client = start(TracewayOptions(attributes = mapOf("tier" to "gold")))
        val body = http.to(OPAMP_URL).first().body
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains(client.getInstanceId()!!))
        assertTrue(text.contains("tier"))
        assertTrue(text.contains("gold"))
    }

    // ---------------------------------------------------------------- emit chains

    @Test
    fun aRuleThatEmitsWhatItMatchesStopsAfterAFewRounds() {
        val client = start(
            TracewayOptions(
                remoteConfig = false,
                collection = mapOf(
                    "signals" to mapOf("custom" to mapOf("mode" to "stream")),
                    "triggers" to listOf(
                        mapOf(
                            "id" to "loop",
                            "steps" to listOf(mapOf("match" to mapOf("key" to "event.name", "op" to "eq", "value" to "ping"))),
                            "actions" to listOf(mapOf("type" to "emit", "eventName" to "ping")),
                        ),
                    ),
                ),
            ),
        )
        client.track("ping")
        client.flush(2000)
        val pings = named("ping").size
        assertTrue("$pings pings", pings in 2..6)
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun initializeReturnsTheRunningClientAndShutdownEndsTheSession() {
        val client = start()
        assertSame(client, TracewayClient.instance)
        val session = client.currentSessionId
        assertNotNull(session)
        client.shutdown(2000)
        assertNull(TracewayClient.instance)
        assertEquals(1, named("session.start").size)
        assertEquals(session, named("session.end").single().attr("session.id"))
    }

    @Test
    fun theSessionStartComesFirstAndCarriesItsStart() {
        start().flush(2000)
        val first = exported().first()
        assertEquals("session.start", first.eventName)
        assertNotNull(first.attr("traceway.session.started_at"))
    }

    @Test
    fun sessionsFalseKeepsTheSessionIdWithoutSessionRecords() {
        val client = start(TracewayOptions(sessions = false))
        client.captureException(RuntimeException("x"))
        client.flush(2000)
        assertTrue(named("session.start").isEmpty())
        assertEquals(client.currentSessionId, named("exception").single().attr("session.id"))
    }

    @Test
    fun aRestartInsideTheSessionKeepsItsIdAndBuffer() {
        val dir = tmp.newFolder("state")
        var client = start(stateDir = dir, inMemory = false)
        val session = client.currentSessionId
        client.log("before the restart")
        client.simulateLifecycle(foreground = false)
        client.flush(2000)
        client.shutdown(2000)

        http.clear()
        client = start(stateDir = dir, inMemory = false)
        assertEquals(session, client.currentSessionId)
        client.captureException(IllegalStateException("after the restart"))
        client.flush(2000)
        val sent = http.to(LOGS_URL).flatMap { Otlp.logs(it.plain) }
        assertTrue(sent.map { it.body }.toString(), sent.any { it.body == "before the restart" })
        assertTrue(sent.any { it.eventName == "exception" })
        assertTrue(sent.any { it.eventName == "traceway.session.update" })
    }

    @Test
    fun standaloneSendsEverythingToTheCollectorAndNeverPolls() {
        val collector = "https://collector.example.com/otlp"
        val client = start(
            TracewayOptions(telemetry = TracewayTelemetry(url = collector)),
            inMemory = false,
            connectionString = null,
        )
        client.captureException(RuntimeException("standalone"))
        client.flush(2000)
        assertTrue(http.to(OPAMP_URL).isEmpty())
        val sent = http.requests.filter { it.url == "$collector/v1/logs" }
        assertTrue(sent.isNotEmpty())
        assertFalse(sent.any { it.headers.containsKey("Authorization") })
        assertTrue(http.requests.none { it.url.startsWith("https://traceway.test") })
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun aRemoteBufferSizeIsAppliedUpToWhatTheDeviceCanHold() {
        remoteDocument = """{"schemaVersion":1,"buffer":{"maxBytes":67108864}}"""
        val client = TracewayClient.initializeForTest(
            CONNECTION,
            TracewayOptions(),
            TracewayTestOverrides(logExporter = logs, spanExporter = spans, http = http, scheduler = scheduler, maxHeapBytes = 256L * 1024 * 1024),
        )
        assertEquals("the server's value is taken", 64L * 1024 * 1024, client.settings.bufferMaxBytes)
        assertEquals("a sixteenth of a 256 MB heap", 16L * 1024 * 1024, client.bufferLimitBytes)
        assertEquals(64L * 1024 * 1024, deviceBufferCapBytes(4096L * 1024 * 1024))
        remoteDocument = null
        assertEquals("the built-in 2 MiB is below every cap", 2L * 1024 * 1024, start().bufferLimitBytes)
    }

    @Test
    fun flushAfterShutdownDoesNotWaitItsWholeTimeout() {
        val client = TracewayClient.initializeForTest(
            CONNECTION,
            TracewayOptions(),
            TracewayTestOverrides(logExporter = logs, spanExporter = spans, http = http, scheduler = ExecutorScheduler()),
        )
        assertTrue(client.awaitReady())
        client.shutdown(2000)
        val started = System.nanoTime()
        client.flush(3000)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 1000)
    }

    // ---------------------------------------------------------------- crashes

    @Test
    fun aCrashIsWrittenToDiskWithItsBreadcrumbsAndSentAtTheNextStart() {
        val dir = tmp.newFolder("state")
        var client = start(stateDir = dir, inMemory = false)
        client.log("right before the crash")
        client.flush(2000)
        // The network is gone: nothing can leave before the process dies.
        http.handler = { HttpResponse(0) }
        client.simulateCrash(Thread.currentThread(), IllegalStateException("fatal"))
        client.shutdown(2000)

        http.handler = { request -> serve(request) }
        http.clear()
        client = start(stateDir = dir, inMemory = false)
        client.flush(5000)
        val sent = http.to(LOGS_URL).flatMap { Otlp.logs(it.plain) }
        val crash = sent.single { it.eventName == "exception" }
        assertEquals("true", crash.attributes["exception.unhandled"])
        assertEquals("fatal", crash.attributes["exception.message"])
        assertTrue(sent.map { it.body }.toString(), sent.any { it.body == "right before the crash" })
    }

    @Test
    fun aCrashIsSentWhenNothingIsKeptOnDisk() {
        // persistToDisk = false, or no usable state directory: there is no outbox to write the crash to.
        val client = start(TracewayOptions(persistToDisk = false), stateDir = null, inMemory = false)
        client.log("right before the crash")
        client.simulateCrash(Thread.currentThread(), IllegalStateException("fatal"))
        // The process would end here: what was not sent by now is lost.
        val sent = http.to(LOGS_URL).flatMap { Otlp.logs(it.plain) }
        val crash = sent.singleOrNull { it.eventName == "exception" }
        assertNotNull("the crash left before the process ended: ${sent.map { it.eventName }}", crash)
        assertEquals("true", crash!!.attributes["exception.unhandled"])
        assertEquals("fatal", crash.attributes["exception.message"])
        assertTrue("with its breadcrumbs", sent.any { it.body == "right before the crash" })
    }

    @Test
    fun aCrashIsNotCollapsedIntoTheReportJustBeforeIt() {
        val client = start()
        val error = IllegalStateException("report and rethrow")
        client.captureException(error)
        client.simulateCrash(Thread.currentThread(), error)
        client.flush(2000)
        val errors = named("exception")
        assertEquals(2, errors.size)
        assertEquals(true, errors[1].attr("exception.unhandled"))
    }

    @Test
    fun aCrashFromBeforeTheStartIsReportedAtTheNextOne() {
        val dir = tmp.newFolder("state")
        File(dir, "crash.json").writeText(
            Json.stringify(
                mapOf(
                    "timeMs" to scheduler.now() - 5000,
                    "type" to "java.lang.NullPointerException",
                    "message" to "early",
                    "stacktrace" to "java.lang.NullPointerException: early\n\tat com.example.App.onCreate(App.kt:10)",
                    "thread" to "main",
                ),
            ),
        )
        val client = start(stateDir = dir)
        client.flush(2000)
        val crash = named("exception").single()
        assertEquals("early", crash.attr("exception.message"))
        assertEquals(true, crash.attr("traceway.crash.before_start"))
        assertFalse(File(dir, "crash.json").exists())
    }
}
