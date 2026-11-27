package com.tracewayapp.traceway

import android.app.Application
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.ProcessName
import com.tracewayapp.traceway.internal.Rfc3339
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.otel.ServerProblems
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.SocketTimeoutException

/**
 * CONTRACT §16 and the ease-of-use findings of the second Android review:
 * the start API (`TracewayOptions(token, baseUrl)`, the deprecated
 * connection string), no `/api/report` ever, Java callers, and what the
 * SDK says when a new user gets stuck.
 */
@RunWith(RobolectricTestRunner::class)
class StartApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler = ManualScheduler(nowMs = System.currentTimeMillis())
    private val http = FakeHttp()

    private class Logs : LogRecordExporter {
        val items = java.util.Collections.synchronizedList(ArrayList<LogRecordData>())
        val all: List<LogRecordData> get() = synchronized(items) { ArrayList(items) }
        var failing = false
        override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
            if (failing) return CompletableResultCode.ofFailure()
            items.addAll(logs)
            return CompletableResultCode.ofSuccess()
        }
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private val logs = Logs()

    @Before
    fun setUp() {
        ShadowLog.clear()
        Traceway.resetWarningsForTest()
        ServerProblems.resetForTest()
    }

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
        Traceway.shutdown(2000)
        ProcessName.override = null
        ServerProblems.resetForTest()
    }

    private fun overrides(
        inMemory: Boolean = true,
        transport: FakeHttp = http,
        legacy: File? = null,
        readAsset: ((String) -> java.io.InputStream?)? = null,
        startThread: ((Runnable) -> Unit)? = null,
        appStateDirectory: Boolean = false,
    ) = TracewayTestOverrides(
        logExporter = if (inMemory) logs else null,
        http = transport,
        scheduler = scheduler,
        legacyDirectory = legacy,
        readAsset = readAsset,
        startThread = startThread,
        appStateDirectory = appStateDirectory,
        random = { 0.5 },
    )

    private fun LogRecordData.attr(key: String): Any? {
        var found: Any? = null
        attributes.forEach { k, v -> if (k.key == key) found = v }
        return found
    }

    private fun LogRecordData.resourceAttr(key: String): String? = resource.attributes.get(AttributeKey.stringKey(key))

    /** The warnings and debug lines tagged `Traceway`, as `W message` / `D message`. */
    private fun sdkLog(): List<String> = ShadowLog.getLogsForTag("Traceway").map {
        val level = when (it.type) {
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            android.util.Log.DEBUG -> "D"
            else -> "?"
        }
        "$level ${it.msg}"
    }

    // ---- the start API --------------------------------------------------------------------

    @Test
    fun theTokenAndTheServerComeFromTheOptions() {
        TracewayClient.initializeForTest(null, TracewayOptions(token = " tok ", baseUrl = "traceway.test/api/report"), overrides())
        val opamp = http.requests.first { it.url.endsWith("/opamp") }
        assertEquals("the server, normalized like an old connection string", "https://traceway.test/api/otel/v1/opamp", opamp.url)
        assertEquals("Bearer tok", opamp.headers["Authorization"])
    }

    @Test
    fun withoutABaseUrlTheServerIsTracewayCloud() {
        // A fake transport: nothing leaves the test.
        val client = TracewayClient.initializeForTest(null, TracewayOptions(token = "tok"), overrides())
        assertEquals("https://cloud.tracewayapp.com", TracewayOptions.DEFAULT_BASE_URL)
        assertEquals("https://cloud.tracewayapp.com/api/otel/v1/opamp", http.requests.first { it.url.endsWith("/opamp") }.url)
        assertEquals(TracewayOptions.DEFAULT_BASE_URL, client.options.baseUrl)
        // A null server from Java is the default too.
        http.clear()
        TracewayClient.initializeForTest(null, TracewayOptions(token = "tok", baseUrl = null), overrides())
        assertTrue(http.requests.any { it.url == "https://cloud.tracewayapp.com/api/otel/v1/opamp" })
    }

    @Test
    fun theNewStartWorksThroughTheFacade() {
        val client = Traceway.init(app, TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false))
        assertNotNull(client)
        assertEquals("tok", client!!.options.token)
        assertTrue("no deprecation warning for the new form", sdkLog().none { "deprecated" in it })
    }

    @Test
    fun theOldConnectionStringStillStartsAndWarnsOnce() {
        val first = Traceway.init(app, "  tok@traceway.test/api/report  ", TracewayOptions(remoteConfig = false, environment = "prod"))
        assertNotNull(first)
        assertEquals("tok", first!!.options.token)
        assertEquals("https://traceway.test", first.options.baseUrl)
        assertEquals("the other options are kept", "prod", first.options.environment)
        first.shutdown(2000)
        val second = Traceway.init(app, "tok@https://traceway.test", TracewayOptions(remoteConfig = false))
        assertNotNull(second)
        val warnings = sdkLog().filter { "deprecated" in it }
        assertEquals(sdkLog().toString(), 1, warnings.size)
        assertTrue(
            warnings.single(),
            warnings.single() == "W Traceway: connection strings are deprecated; start with " +
                "Traceway.init(application, TracewayOptions(token = \"YOUR_TOKEN\", baseUrl = \"https://traceway.test\"))",
        )
        assertFalse("the token is never logged", sdkLog().any { "tok@" in it })
    }

    @Test
    fun aNullConnectionStringUsesTheOptions() {
        @Suppress("DEPRECATION")
        val client = Traceway.init(app, null as String?, TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false))
        assertNotNull(client)
    }

    @Test
    fun invalidStartsWarnAndLeaveTheSdkOffWithoutThrowing() {
        for (bad in listOf("@https://cloud.tracewayapp.com", "tok", "tok@", "tok@ftp://host", "", "   ")) {
            ShadowLog.clear()
            assertNull(bad, Traceway.init(app, bad, TracewayOptions(remoteConfig = false)))
            assertNull(TracewayClient.instance)
            val lines = sdkLog()
            assertEquals("$bad: $lines", 1, lines.size)
            assertTrue(lines.single(), lines.single().startsWith("W Traceway is not running: Invalid"))
        }
        assertNull(Traceway.init(app, TracewayOptions(token = "tok", baseUrl = "ftp://host")))
        assertTrue(sdkLog().last(), sdkLog().last().contains("Invalid Traceway server URL \"ftp://host\""))
        assertNull(Traceway.init(app, TracewayOptions()))
        assertTrue(sdkLog().last(), sdkLog().last().contains("TracewayOptions has no token"))
        assertNull(Traceway.init(app, null as TracewayOptions?))
        assertNull(Traceway.init(null, TracewayOptions(token = "tok")))
        // Standalone needs no token, only a telemetry destination.
        assertNotNull(
            TracewayClient.initializeForTest(null, TracewayOptions(telemetry = TracewayTelemetry(url = "https://collector.test")), overrides()),
        )
    }

    // ---- no /api/report, ever ----------------------------------------------------------------

    private fun oneXReport(dir: File, name: String, stackTrace: String, isMessage: Boolean = false) {
        val at = Rfc3339.format(System.currentTimeMillis() - 60_000)
        File(dir, name).writeText(
            Json.stringify(
                mapOf(
                    "createdAt" to at,
                    "exception" to mapOf(
                        "stackTrace" to stackTrace,
                        "recordedAt" to at,
                        "attributes" to mapOf("user.id" to "u-1"),
                        "isMessage" to isMessage,
                    ),
                ),
            ),
        )
    }

    @Test
    fun oneXReportsBecomeRecordsOfThisSdkAndNothingEverRequestsApiReport() {
        val pending = tmp.newFolder("traceway_pending")
        val betaKey = com.tracewayapp.traceway.core.OtlpDestination("https://TRACEWAY.test:443/api/report", mapOf("Authorization" to "Bearer tok")).key
        val mine = File(pending, betaKey).apply { mkdir() }
        oneXReport(mine, "a.json", "java.lang.IllegalStateException: legacy error\n\tat com.example.Main.run(Main.kt:10)")
        oneXReport(mine, "b.json", "Support chat opened", isMessage = true)
        TracewayClient.initializeForTest("other@https://traceway.test", TracewayOptions(), overrides(legacy = pending)).shutdown(2000)
        assertTrue(logs.all.none { it.eventName == "exception" })
        assertEquals(2, mine.listFiles()!!.size)
        logs.failing = true
        TracewayClient.initializeForTest("tok@https://TRACEWAY.test:443/api/report", TracewayOptions(), overrides(legacy = pending)).shutdown(2000)
        assertEquals("a failed handoff retains both source files", 2, mine.listFiles()!!.size)
        logs.failing = false
        val client = TracewayClient.initializeForTest("tok@https://TRACEWAY.test:443/api/report", TracewayOptions(), overrides(legacy = pending))
        client.flush(2000)
        val exception = logs.all.single { it.eventName == "exception" }
        assertEquals("java.lang.IllegalStateException", exception.attr("exception.type"))
        assertEquals("legacy error", exception.attr("exception.message"))
        assertTrue((exception.attr("exception.stacktrace") as String).contains("com.example.Main.run(Main.kt:10)"))
        assertEquals(true, exception.attr("traceway.previous_run"))
        assertEquals("u-1", exception.attr("user.id"))
        val message = logs.all.single { it.eventName == "traceway.message" }
        assertEquals("Support chat opened", message.bodyValue?.asString())
        assertFalse("the 1.x files are gone", pending.walk().any { it.isFile })

        // A whole life of the client, with its real exporters: issues, a flush, a restart, a shutdown.
        val restarted = TracewayClient.initializeForTest("tok@https://traceway.test/api/report", TracewayOptions(), overrides(inMemory = false))
        restarted.captureException(IllegalStateException("x"))
        restarted.captureMessage("m")
        restarted.flush(2000)
        restarted.shutdown(2000)
        assertTrue(http.requests.any { it.url == "https://traceway.test/api/otel/v1/logs" })
        assertEquals(emptyList<String>(), http.requests.map { it.url }.filter { "/api/report" in it })
    }

    // ---- Java ------------------------------------------------------------------------------

    private fun nullArgs(types: Array<Class<*>>): Array<Any?> = types.map { type ->
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            // A Java caller passes `(Pattern) null` to a vararg setter, not a null array.
            else -> if (type.isArray) java.lang.reflect.Array.newInstance(type.componentType, 1) else null
        }
    }.toTypedArray()

    @Test
    fun javaNullsForTheOptionsNeverThrow() {
        val thrown = ArrayList<String>()
        fun attempt(what: String, call: () -> Any?) {
            try {
                call()
            } catch (e: InvocationTargetException) {
                thrown.add("$what: ${e.targetException}")
            }
        }
        val built = ArrayList<TracewayOptions>()
        for (c in TracewayOptions::class.java.constructors) {
            attempt("TracewayOptions(${c.parameterCount})") { built.add(c.newInstance(*nullArgs(c.parameterTypes)) as TracewayOptions) }
        }
        for (type in listOf(TracewayTelemetry::class.java, TracewayTelemetryEndpoint::class.java)) {
            for (c in type.constructors) attempt("${type.simpleName}(${c.parameterCount})") { c.newInstance(*nullArgs(c.parameterTypes)) }
        }
        val builder = TracewayOptions.builder()
        val setters = TracewayOptions.Builder::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue("one setter per option", setters.size > 35)
        for (m in setters) attempt("Builder.${m.name}") { m.invoke(builder, *nullArgs(m.parameterTypes)) }
        val telemetry = TracewayTelemetry.builder()
        for (m in TracewayTelemetry.Builder::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }) {
            attempt("TracewayTelemetry.Builder.${m.name}") { m.invoke(telemetry, *nullArgs(m.parameterTypes)) }
        }
        assertEquals(emptyList<String>(), thrown)

        // Null means the default: the builder of nulls builds the defaults.
        assertEquals(TracewayOptions(), builder.build())
        assertEquals(TracewayTelemetry(), telemetry.build())
        // Options made with Java nulls start the SDK.
        val nulls = built.first { it.baseUrl == null && it.attributes == null }.copy(token = "tok", remoteConfig = true)
        val client = TracewayClient.initializeForTest(null, nulls, overrides())
        client.captureException(RuntimeException("with null options"))
        client.flush(2000)
        assertEquals("https://cloud.tracewayapp.com/api/otel/v1/opamp", http.requests.first { it.url.endsWith("/opamp") }.url)
    }

    @Test
    fun theBuilderSetsEveryOptionByName() {
        val pattern = java.util.regex.Pattern.compile("^https://api\\.")
        val options = TracewayOptions.builder()
            .token("tok")
            .baseUrl("https://traceway.test")
            .screenCapture(true)
            .attributes(mapOf("tier" to "gold"))
            .ignoreErrors(listOf("Boom", null, Regex("x")))
            .propagateTraceHeaderUrlPatterns(pattern, null)
            .appHangThresholdMs(500L)
            .reportHangsInDebug(true)
            .build()
        assertEquals(
            TracewayOptions(
                token = "tok",
                baseUrl = "https://traceway.test",
                screenCapture = true,
                attributes = mapOf("tier" to "gold"),
                ignoreErrors = options.ignoreErrors,
                propagateTraceHeaderUrlPatterns = options.propagateTraceHeaderUrlPatterns,
                appHangThresholdMs = 500L,
                reportHangsInDebug = true,
            ),
            options,
        )
        assertEquals(listOf("Boom", "x"), options.ignoreErrors!!.map { it.toString() })
        assertEquals(listOf("^https://api\\."), options.propagateTraceHeaderUrlPatterns!!.map { it.pattern })
        assertEquals("toBuilder keeps the rest", options.copy(debug = true), options.toBuilder().debug(true).build())
        assertEquals(
            TracewayTelemetry(url = "https://collector.test", headers = mapOf("k" to "v")),
            TracewayTelemetry.builder().url("https://collector.test").headers(mapOf("k" to "v")).build(),
        )
    }

    @Test
    fun anInteractionCanBeMadeCurrentForATryWithResources() {
        val client = TracewayClient.initializeForTest("tok@https://traceway.test", TracewayOptions(recordAllSessions = true), overrides())
        val checkout = client.startInteraction("checkout")
        checkout.makeCurrent().use { client.log("inside") }
        client.log("outside")
        val scope = checkout.makeCurrent()
        scope.close()
        scope.close()
        checkout.end()
        client.flush(2000)
        val inside = logs.all.single { it.bodyValue?.asString() == "inside" }
        val outside = logs.all.single { it.bodyValue?.asString() == "outside" }
        assertEquals(checkout.traceId, inside.spanContext.traceId)
        assertFalse(outside.spanContext.isValid)
        // An interaction that records nothing gives a scope that does nothing.
        TracewayInteraction(null) { _, _ -> }.makeCurrent().close()
    }

    @Test
    fun startRequestHasAJavaOverloadWithoutTheTraceparentFlag() {
        val m = TracewayClient::class.java.getMethod("startRequest", String::class.java, String::class.java)
        assertNotNull(m)
    }

    // ---- defaults that need no code ----------------------------------------------------------

    @Test
    fun theAppsVersionNameIsTheDefaultServiceVersion() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).versionName = "3.4.5"
        fun versionOf(options: TracewayOptions): String? {
            logs.items.clear()
            val client = TracewayClient.initialize(app, options, overrides(startThread = { it.run() }))
            client.captureException(RuntimeException("v"))
            client.flush(2000)
            client.shutdown(2000)
            return logs.all.first { it.eventName == "exception" }.resourceAttr("service.version")
        }
        val base = TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false)
        assertEquals("3.4.5", versionOf(base))
        assertEquals("9.9", versionOf(base.copy(version = "9.9")))
        assertEquals("empty is the default", "3.4.5", versionOf(base.copy(version = "")))
    }

    @Test
    fun theGradlePluginsProguardUuidIsReadFromTheAssets() {
        val uuid = "6a8cb813-45f6-3652-ad33-778fd1eab196"
        val asset = { name: String -> if (name == "traceway/proguard-uuid.txt") "$uuid\n".byteInputStream() else null }
        fun uuidOf(options: TracewayOptions, read: ((String) -> java.io.InputStream?)?): String? {
            logs.items.clear()
            val client = TracewayClient.initializeForTest("tok@https://traceway.test", options, overrides(readAsset = read))
            client.captureException(RuntimeException("u"))
            client.flush(2000)
            return logs.all.first { it.eventName == "exception" }.resourceAttr("app.debug.proguard_uuid")
        }
        assertEquals(uuid, uuidOf(TracewayOptions(), asset))
        assertEquals("the app's own wins", "pinned", uuidOf(TracewayOptions(proguardUuid = "pinned"), asset))
        assertNull("no plugin, no uuid", uuidOf(TracewayOptions(), { null }))
        assertNull("an unreadable asset is none", uuidOf(TracewayOptions(), { throw java.io.IOException("no asset") }))
    }

    /** AND2-2, CONTRACT §15: the SDK is 2.1.0; `telemetry.sdk.version` stays OpenTelemetry's own. */
    @Test
    fun theSdkReportsItselfAs210() {
        val client = TracewayClient.initializeForTest("tok@https://traceway.test", TracewayOptions(), overrides())
        client.captureException(RuntimeException("v"))
        client.flush(2000)
        val record = logs.all.first { it.eventName == "exception" }
        assertEquals("2.1.0", record.resourceAttr("traceway.sdk.version"))
        assertEquals("1.68.0", record.resourceAttr("telemetry.sdk.version"))
        assertEquals("2.1.0", record.instrumentationScopeInfo.version)
    }

    // ---- processes and the main thread ---------------------------------------------------------

    @Test
    fun anotherProcessGetsNoClientUnlessAllProcesses() {
        ProcessName.override = "${app.packageName}:sync"
        val options = TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false, debug = true)
        assertNull(Traceway.init(app, options))
        assertTrue(sdkLog().toString(), sdkLog().any { it == "D Traceway runs in the app's main process only (this is ${app.packageName}:sync); set allProcesses to run here" })
        val client = Traceway.init(app, options.copy(allProcesses = true))
        assertNotNull(client)
        assertTrue(client!!.awaitReady())
        client.flush(2000)
        assertTrue("its own state directory", File(app.applicationInfo.dataDir, "no_backup/traceway_v2-sync").isDirectory)
        client.shutdown(2000)
        ProcessName.override = app.packageName
        assertNotNull("the main process", Traceway.init(app, options))
    }

    @Test
    fun initTouchesNoDiskUntilTheStartThreadRuns() {
        val noBackup = File(app.applicationInfo.dataDir, "no_backup")
        noBackup.deleteRecursively()
        var start: Runnable? = null
        val client = TracewayClient.initialize(
            app,
            TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false),
            overrides(appStateDirectory = true, startThread = { start = it }),
        )
        assertFalse("Traceway.init made no directory on the main thread", noBackup.exists())
        start!!.run()
        client.flush(2000)
        assertTrue("the start thread keeps the state where it always was", File(noBackup, "traceway_v2").isDirectory)
    }

    // ---- what a new user is told -----------------------------------------------------------------

    @Test
    fun callsBeforeInitWarnOnce() {
        TracewayClient.everStarted = false
        Traceway.captureException(RuntimeException("early"))
        Traceway.log("early")
        Traceway.track("early")
        val warnings = sdkLog().filter { "before Traceway.init" in it }
        assertEquals(sdkLog().toString(), 1, warnings.size)
        assertEquals(
            "W Traceway.captureException was called before Traceway.init, so nothing is recorded. Call Traceway.init in " +
                "Application.onCreate (is android:name set on <application> in AndroidManifest.xml?)",
            warnings.single(),
        )
        // After a shutdown the app chose to stop it: no warning.
        TracewayClient.initializeForTest("tok@https://traceway.test", TracewayOptions(), overrides()).shutdown(2000)
        Traceway.resetWarningsForTest()
        ShadowLog.clear()
        Traceway.captureMessage("late")
        assertTrue(sdkLog().none { "before Traceway.init" in it })
    }

    @Test
    fun aDroppedErrorSaysWhyInDebug() {
        val client = TracewayClient.initializeForTest(
            "tok@https://traceway.test",
            TracewayOptions(debug = true, beforeCapture = { it.message != "no" }),
            overrides(),
        )
        client.captureException(SocketTimeoutException("timeout"))
        client.captureException(RuntimeException("no"))
        val lines = sdkLog()
        assertTrue(
            lines.toString(),
            lines.contains(
                "D dropped java.net.SocketTimeoutException: timeout, which matches ignoreErrors \"SocketTimeoutException\" " +
                    "(DEFAULT_IGNORE_ERRORS); set ignoreErrors = emptyList() to report it",
            ),
        )
        assertTrue(lines.toString(), lines.contains("D dropped java.lang.RuntimeException: no by beforeCapture"))
        TracewayClient.initializeForTest("tok@https://traceway.test", TracewayOptions(debug = true, sampleRate = 0.25), overrides())
            .captureMessage("sampled out")
        assertTrue(sdkLog().toString(), sdkLog().contains("D dropped a message: not sampled (sampleRate 0.25)"))
        assertTrue(lines.toString(), lines.any { it.startsWith("D console, network, navigation, userActions and custom records are kept on the device") })
    }

    @Test
    fun aRejectedTokenWarnsOnceWhateverDebugSays() {
        val rejecting = FakeHttp { r -> HttpResponse(if (r.url.endsWith("/api/otel/v1/logs")) 401 else 200) }
        val client = TracewayClient.initializeForTest("tok@https://traceway.test", TracewayOptions(), overrides(inMemory = false, transport = rejecting))
        client.captureException(RuntimeException("one"))
        client.flush(2000)
        client.captureException(RuntimeException("two"))
        client.flush(2000)
        val warnings = sdkLog().filter { "rejected" in it }
        assertEquals(sdkLog().toString(), 1, warnings.size)
        assertEquals(
            "W Traceway: the server rejected the project token (HTTP 401) at https://traceway.test. Copy the token from " +
                "the project's Connection page into TracewayOptions(token = …)",
            warnings.single(),
        )
        assertTrue(
            ServerProblems.message("https://traceway.test/api/otel/v1/logs", 404).startsWith(
                "Traceway: https://traceway.test does not accept Android 2.x telemetry (HTTP 404 on /api/otel/v1/logs)",
            ),
        )
        assertTrue(ServerProblems.message("https://collector.test/v1/logs", 403).contains("telemetry receiver https://collector.test/v1/logs"))
    }
}
