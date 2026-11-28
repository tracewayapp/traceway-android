package com.tracewayapp.traceway

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tracewayapp.traceway.diagnostics.AppHangs
import com.tracewayapp.traceway.diagnostics.ExitReasons
import com.tracewayapp.traceway.diagnostics.ExitRecord
import com.tracewayapp.traceway.diagnostics.ExitSource
import com.tracewayapp.traceway.interaction.PointerListener
import com.tracewayapp.traceway.interaction.TapListener
import com.tracewayapp.traceway.interaction.TouchTracker
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.ErrorHandler
import com.tracewayapp.traceway.internal.HttpResponse
import com.tracewayapp.traceway.internal.HttpTransport
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.internal.NavigationCapture
import com.tracewayapp.traceway.metrics.HangListener
import com.tracewayapp.traceway.metrics.HangWatchdog
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.wireframe.RealScreen
import com.tracewayapp.traceway.wireframe.TestActivity
import com.tracewayapp.traceway.wireframe.ViewFrameSource
import com.tracewayapp.traceway.wireframe.WireframeImages
import com.tracewayapp.traceway.wireframe.WireframeRoots
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private const val CONNECTION = "tok@https://traceway.test"

/** Keeps what is exported, or fails every call when [failing]. */
private class Logs : LogRecordExporter {
    @Volatile
    var failing = false
    val failedExport = CountDownLatch(1)
    private val items = Collections.synchronizedList(ArrayList<LogRecordData>())
    val all: List<LogRecordData> get() = synchronized(items) { ArrayList(items) }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (failing) {
            failedExport.countDown()
            throw IllegalStateException("exporter")
        }
        items.addAll(logs)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = if (failing) throw IllegalStateException("exporter") else CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private class Spans : SpanExporter {
    @Volatile
    var failing = false

    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        if (failing) throw IllegalStateException("exporter") else CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

/**
 * CONTRACT §13: the SDK never takes the app down. Internal pieces are made
 * to fail (exporter, transport, wireframe capture, image encoder, settings,
 * the app's own callbacks) and the app's operation must still complete,
 * with the SDK working afterwards; every public API is called with Java
 * nulls, before init, after shutdown and from many threads at once; and
 * the app's own crashes still reach the handler that was there before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler = ManualScheduler(ioInline = true)
    private val http = FakeHttp()
    private val logs = Logs()
    private val spans = Spans()
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        TracewayClient.resetForTest()
        Traceway.shutdown(2000)
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
    }

    private fun start(
        options: TracewayOptions = TracewayOptions(),
        transport: HttpTransport = http,
    ): TracewayClient = TracewayClient.initializeForTest(
        CONNECTION,
        options,
        TracewayTestOverrides(logExporter = logs, spanExporter = spans, http = transport, scheduler = scheduler, random = { 0.5 }),
    )

    private fun exceptions(): List<String?> =
        logs.all.filter { it.eventName == "exception" }.map { r ->
            var message: String? = null
            r.attributes.forEach { k, v -> if (k.key == "exception.message") message = v as String }
            message
        }

    // ---- the public API ---------------------------------------------------------

    /** A value for each parameter: null for objects (what a Java caller can pass), zero for primitives. */
    private fun nullArgs(m: Method): Array<Any?> = m.parameterTypes.map { type ->
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    }.toTypedArray()

    /** Calls every method [methods] holds with Java nulls; the ones that throw into the caller. */
    private fun callWithNulls(target: Any?, methods: List<Method>): List<String> {
        val thrown = ArrayList<String>()
        for (m in methods) {
            try {
                m.invoke(target, *nullArgs(m))
            } catch (e: InvocationTargetException) {
                thrown.add("${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${e.targetException}")
            }
        }
        return thrown
    }

    private fun facade(): List<Method> = Traceway::class.java.declaredMethods.filter {
        Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name &&
            it.name != "shutdown"
    }.sortedBy { it.name + it.parameterCount }

    private fun clientMethods(): List<Method> = TracewayClient::class.java.declaredMethods.filter {
        !Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name &&
            it.name != "shutdown" && it.name != "flush"
    }.sortedBy { it.name + it.parameterCount }

    @Test
    fun javaNullsNeverReachTheApp() {
        assertTrue(facade().size > 30)
        assertEquals("before init", emptyList<String>(), callWithNulls(null, facade()))
        val client = start()
        assertEquals("on the facade", emptyList<String>(), callWithNulls(null, facade()))
        assertEquals("on the client", emptyList<String>(), callWithNulls(client, clientMethods()))
        val interaction = Traceway.startInteraction("checkout")
        // run and makeCurrent are called in their own tests: a scope left open would outlive this one.
        val interactionMethods = TracewayInteraction::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name && it.name != "run" && it.name != "makeCurrent"
        }
        assertEquals("on an interaction", emptyList<String>(), callWithNulls(interaction, interactionMethods))

        // A Java map with null keys and values, in the options and in calls.
        val nulls = HashMap<String?, String?>().apply {
            put(null, "x")
            put("k", null)
            put("tier", "gold")
        }
        @Suppress("UNCHECKED_CAST")
        val hostile = nulls as Map<String, String>
        Traceway.captureException(RuntimeException("with nulls"), hostile)
        Traceway.setAttributes(hostile)
        Traceway.log("line", TracewayLogLevel.INFO, hostile)
        client.flush(2000)
        assertTrue(exceptions().contains("with nulls"))
        val restarted = start(TracewayOptions(attributes = hostile, metricDimensions = hostile))
        restarted.captureException(RuntimeException("after"))
        restarted.flush(2000)
        assertTrue(exceptions().contains("after"))
        // The OpAMP description is built from those attributes on every poll.
        assertTrue(http.requests.any { it.url.endsWith("/opamp") && String(it.body, Charsets.ISO_8859_1).contains("gold") })
    }

    @Test
    fun aJavaMapWithANullKeyDoesNotStopInit() {
        val dims = HashMap<String?, String>().apply { put(null, "x") }
        @Suppress("UNCHECKED_CAST")
        val options = TracewayOptions(metricDimensions = dims as Map<String, String>, remoteConfig = false)
        val client = Traceway.init(app, CONNECTION, options)
        assertNotNull("the SDK runs without the bad entry", client)
        assertSame(client, Traceway.client)
    }

    private val hostileMap = object : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>> get() = throw IllegalStateException("map")
        override val size get() = 1
    }

    @Test
    fun hostileAttributeMapsAreContained() {
        val c = start()
        c.captureException(RuntimeException("x"), hostileMap)
        c.captureMessage("m", hostileMap)
        c.log("l", TracewayLogLevel.INFO, hostileMap)
        c.setAttributes(hostileMap)
        c.track("event", hostileMap)
        Traceway.startInteraction("i").captureException(RuntimeException("y"), hostileMap)
        c.captureException(RuntimeException("after"))
        c.flush(2000)
        assertTrue("an exception with an unreadable map is still reported", exceptions().contains("x"))
        assertTrue(exceptions().contains("after"))
    }

    @Test
    fun everyCallBeforeInitAndAfterShutdownIsANoOp() {
        fun everything() {
            Traceway.captureException(RuntimeException("e"))
            Traceway.captureMessage("m")
            Traceway.recordAction("c", "n", mapOf("k" to 1))
            Traceway.track("t")
            Traceway.setAttribute("k", "v")
            Traceway.setAttributes(mapOf("a" to "b"))
            Traceway.removeAttribute("k")
            Traceway.clearAttributes()
            Traceway.recordNavigation("/a")
            Traceway.setRoute("/a/:id")
            Traceway.log("l")
            val i = Traceway.startInteraction("i")
            assertEquals(7, i.run { 7 })
            i.setAttribute("k", 1)
            i.end()
            Traceway.reportFullyDisplayed()
            Traceway.metric("custom.x", 1.0)
            Traceway.count("custom.y")
            Traceway.setMetricDimension("d", "v")
            Traceway.mask(View(app))
            Traceway.unmask(View(app))
            Traceway.getInstanceId()
            Traceway.currentSessionId
            Traceway.refreshConfig()
            Traceway.tracer?.spanBuilder("s")?.startSpan()?.end()
            TracewayLogcat.i("T", "logcat")
            TracewayLogcat.println(android.util.Log.ERROR, null, "p", android.util.Log.INFO)
            Traceway.flush(100)
        }
        assertNull(Traceway.tracer)
        everything()
        Traceway.shutdown(100)
        val c = start()
        everything()
        c.shutdown(2000)
        c.shutdown(2000)
        assertNull("after shutdown", Traceway.tracer)
        val began = System.nanoTime()
        everything()
        c.captureException(RuntimeException("after shutdown"))
        c.flush(5000)
        assertTrue("nothing waits after shutdown", System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2))
        assertFalse(exceptions().contains("after shutdown"))
        // And it starts again.
        start().captureException(RuntimeException("again"))
        Traceway.flush(2000)
        assertTrue(exceptions().contains("again"))
    }

    @Test
    fun concurrentCallsFromManyThreadsNeverThrow() {
        val c = start()
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val go = CountDownLatch(1)
        val threads = List(8) { n ->
            Thread {
                go.await()
                try {
                    repeat(150) { i ->
                        Traceway.setAttribute("k$n", "$i")
                        Traceway.captureException(RuntimeException("t$n-$i"), mapOf("i" to "$i"))
                        Traceway.log("line $i")
                        Traceway.recordNavigation("/s$i")
                        Traceway.startInteraction("i$n").run { Traceway.track("e") }
                        Traceway.metric("custom.m", i.toDouble())
                        if (i % 50 == 0) Traceway.removeAttribute("k$n")
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }.apply { start() }
        }
        go.countDown()
        // Shut down while they are still calling.
        Thread.sleep(20)
        c.shutdown(2000)
        threads.forEach { it.join(20_000) }
        assertEquals(emptyList<Throwable>(), failures)
    }

    // ---- fault injection ----------------------------------------------------------

    @Test
    fun explicitFlushWaitsForANewDrainAfterACoalescedRequest() {
        val previous = CompletableResultCode().succeed()
        val fresh = CompletableResultCode()
        var requests = 0
        flushBatchQueue(2000) {
            requests++
            if (requests < 3) previous else fresh.succeed()
        }
        assertEquals(3, requests)
        assertTrue(fresh.isSuccess)
    }

    @Test
    fun aThrowingExporterNeverReachesTheApp() {
        val c = start()
        logs.failing = true
        spans.failing = true
        c.captureException(RuntimeException("lost"))
        c.log("l")
        Traceway.startInteraction("i").apply { run { } ; end() }
        c.flush(2000)
        assertTrue("the exporter actually failed before recovery", logs.failedExport.await(10, TimeUnit.SECONDS))
        logs.failing = false
        spans.failing = false
        c.captureException(RuntimeException("after"))
        c.flush(2000)
        assertTrue(exceptions().contains("after"))
    }

    @Test
    fun aFailingTransportNeverReachesTheApp() {
        val transport = object : HttpTransport {
            override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse =
                throw OutOfMemoryError("transport")
        }
        val c = TracewayClient.initializeForTest(
            CONNECTION,
            TracewayOptions(),
            TracewayTestOverrides(http = transport, scheduler = scheduler),
        )
        c.captureException(RuntimeException("x"))
        c.recordNavigation("/a")
        c.flush(2000)
        c.refreshConfig()
        c.shutdown(2000)
    }

    @Test
    fun garbageFromTheServerLeavesTheSdkWorking() {
        var answer = 0
        http.handler = { request ->
            if (request.url.endsWith("/opamp")) {
                answer++
                // Random bytes, then a body that is not protobuf at all.
                HttpResponse(200, body = if (answer % 2 == 1) ByteArray(64) { (it * 37).toByte() } else "{\"a\":".toByteArray())
            } else {
                HttpResponse(200)
            }
        }
        val c = start()
        c.refreshConfig()
        c.refreshConfig()
        c.captureException(RuntimeException("still reported"))
        c.flush(2000)
        assertTrue(exceptions().contains("still reported"))
    }

    @Test
    fun theAppsCallbacksThatThrowAreContained() {
        val c = start(
            TracewayOptions(
                beforeCapture = { throw OutOfMemoryError("callback") },
                // The app's list of patterns cannot even be read.
                ignoreErrors = object : AbstractList<Any>() {
                    override val size: Int get() = 1
                    override fun get(index: Int): Any = throw IllegalStateException("patterns")
                },
            ),
        )
        c.captureException(RuntimeException("kept too"))
        c.captureMessage("kept")
        c.flush(2000)
        assertEquals("a callback that throws keeps the record", 2, logs.all.count { it.eventName == "exception" || it.eventName == "traceway.message" })
    }

    @Test
    fun wireframeCaptureOfViewsThatThrowNeverReachesTheApp() {
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        val activity = controller.get()
        activity.setContentView(
            FrameLayout(activity).apply {
                addView(object : TextView(activity) {
                    override fun getText(): CharSequence = throw IllegalStateException("app view")
                })
                addView(object : View(activity) {
                    override fun getContentDescription(): CharSequence = throw IllegalStateException("app view")
                })
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val source = ViewFrameSource(WireframeRoots(), activity)
        source.observe { }
        source.capture(1_000L, null)
        source.capture(2_000L, null)
        source.stopObserving()
        controller.pause().stop().destroy()
    }

    @Test
    fun anEncoderThatFailsDropsThePictureOnly() {
        val rejecting = WireframeImages(onReady = {}, encoder = Executor { throw RejectedExecutionException("no thread") }).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertNull(rejecting.lookup(bitmap, 50f, 50f))
        assertTrue(rejecting.all.single().failed)
        assertNull("given up for good", rejecting.lookup(bitmap, 50f, 50f))

        val throwing = WireframeImages(onReady = { throw IllegalStateException("onReady") }, encoder = Executor { it.run() }).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        throwing.lookup(bitmap, 50f, 50f)
        assertTrue(throwing.all.single().failed)
    }

    // ---- hooks ---------------------------------------------------------------------

    @Test
    fun touchListenersThatThrowNeverChangeTheAppsTouch() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val seen = ArrayList<Int>()
        activity.setContentView(object : View(activity) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                seen.add(event.actionMasked)
                return true
            }
        })
        shadowOf(Looper.getMainLooper()).idle()
        val content = activity.findViewById<View>(android.R.id.content)
        val at = IntArray(2)
        content.getLocationInWindow(at)
        val x = at[0] + content.width / 2f
        val y = at[1] + content.height / 2f
        val tracker = TouchTracker()
        tracker.addTapListener(object : TapListener {
            override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) = throw OutOfMemoryError("tap")
        })
        tracker.addPointerListener(object : PointerListener {
            override fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("down")
            override fun pointerMove(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("move")
            override fun pointerUp(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean, tap: Boolean) =
                throw IllegalStateException("up")
            override fun pointerCancel(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) = throw IllegalStateException("cancel")
        })
        tracker.install(app)
        tracker.track(activity.window)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
        assertTrue(activity.window.callback.dispatchTouchEvent(down))
        assertTrue(activity.window.callback.dispatchTouchEvent(up))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen)
        tracker.uninstall()
    }

    private class Chain(private val request: Request, private val answer: (Request) -> Response) : Interceptor.Chain {
        override fun request(): Request = request
        override fun proceed(request: Request): Response = answer(request)
        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 10_000
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 10_000
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun theOkHttpInterceptorGivesTheAppItsOwnResponseAndItsOwnError() {
        start()
        val request = Request.Builder().url("https://api.example.com/cart").build()
        // An inner interceptor's body whose length cannot be read: the SDK reads it, the app never does.
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = throw IllegalStateException("closed source")
            override fun source(): BufferedSource = Buffer()
        }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        assertSame(response, TracewayOkHttpInterceptor().intercept(Chain(request) { response }))

        val io = IOException("offline")
        try {
            TracewayOkHttpInterceptor().intercept(Chain(request) { throw io })
            fail()
        } catch (e: IOException) {
            assertSame(io, e)
        }
        val runtime = IllegalArgumentException("app's")
        try {
            TracewayOkHttpInterceptor().intercept(Chain(request) { throw runtime })
            fail()
        } catch (e: IllegalArgumentException) {
            assertSame(runtime, e)
        }
    }

    @Test
    fun mainThreadHooksContainTheirFailures() {
        val main = Handler(Looper.getMainLooper())
        val watchdog = HangWatchdog(
            clock = { SystemClock.uptimeMillis() },
            postToMain = { main.post(it) },
            schedule = { _, _ -> Cancellable { } },
            thresholdMs = 0,
            intervalMs = 1000,
            onHang = { throw IllegalStateException("aggregator") },
        )
        watchdog.start()
        // Runs the probe in the app's main looper: nothing may come out of it.
        shadowOf(Looper.getMainLooper()).idle()
        watchdog.stop()
    }

    @Test
    fun theAppsCrashStillReachesThePreviousHandlerAndShutdownGivesItBack() {
        val chained = ArrayList<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> chained.add(t to e) }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        val client = Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false))
        assertNotNull(client)
        val installed = Thread.getDefaultUncaughtExceptionHandler()!!
        assertNotEquals(previous, installed)
        val crash = IllegalStateException("app crash")
        val thread = Thread("worker")
        installed.uncaughtException(thread, crash)
        assertEquals("once, the same thread and the same object", listOf<Pair<Thread, Throwable>>(thread to crash), chained)

        Traceway.shutdown(2000)
        assertSame("shutdown gives the handler back", previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun aFailedInitLeavesNothingBehind() {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val callbacks = lifecycleCallbacks()
        try {
            TracewayClient.initialize(
                app,
                TracewayOptions(token = "tok", baseUrl = "https://traceway.test"),
                TracewayTestOverrides(instrument = true, startThread = { throw OutOfMemoryError("pthread_create (1040KB stack) failed") }),
            )
            fail()
        } catch (_: OutOfMemoryError) {
        }
        assertNull(TracewayClient.instance)
        assertSame("the crash handler is given back", handler, Thread.getDefaultUncaughtExceptionHandler())
        assertEquals("no activity callbacks left", callbacks, lifecycleCallbacks())
        // Through the facade the failure is contained, and the next init works.
        assertNotNull(Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false)))
    }

    @Test
    fun aHookThatCannotBeInstalledLeavesTheOthers() {
        val client = TracewayClient.initialize(
            app,
            TracewayOptions(token = "tok", baseUrl = "https://traceway.test", remoteConfig = false),
            TracewayTestOverrides(instrument = true, beforeInstallStep = { if (it == "taps") throw IllegalStateException("taps") }),
        )
        assertSame(client, TracewayClient.instance)
        assertNotSame(defaultHandler, Thread.getDefaultUncaughtExceptionHandler())
        client.shutdown(2000)
    }

    private fun assertNotSame(a: Any?, b: Any?) = assertFalse(a === b)

    /** The activity lifecycle callbacks registered on the application now. */
    private fun lifecycleCallbacks(): Int {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        return (field.get(app) as java.util.ArrayList<*>).size
    }

    @Test
    fun withoutJavaUtilFunctionInitDoesNothingInsteadOfCrashing() {
        val check = TracewayClient.platformCheck
        TracewayClient.platformCheck = { throw NoClassDefFoundError("java/util/function/Function") }
        try {
            assertNull(Traceway.init(app, CONNECTION))
            assertNull(TracewayClient.instance)
            Traceway.captureException(RuntimeException("x"))
            Traceway.log("l")
        } finally {
            TracewayClient.platformCheck = check
        }
    }

    @Test
    fun theInstanceIdIsNeverTheNilUuid() {
        val client = Traceway.init(app, CONNECTION, TracewayOptions(remoteConfig = false))!!
        assertNotEquals("00000000-0000-0000-0000-000000000000", Traceway.getInstanceId())
        assertTrue(client.awaitReady())
        val id = Traceway.getInstanceId()
        assertNotNull(id)
        assertNotEquals("00000000-0000-0000-0000-000000000000", id)
    }

    // ---- shutdown leaves nothing behind ------------------------------------------------

    class ScreenFragment : Fragment() {
        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: android.os.Bundle?): View =
            View(inflater.context)
    }

    @Test
    fun anUninstalledNavigationCaptureNoLongerFollowsFragments() {
        val seen = ArrayList<String>()
        val navigation = NavigationCapture { path, _, _ -> seen.add(path) }
        val controller = Robolectric.buildActivity(TestActivity::class.java).create()
        val activity = controller.get()
        navigation.onActivityCreated(activity, null)
        controller.start().resume()
        navigation.onActivityResumed(activity)
        navigation.uninstall()
        val container = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(container)
        activity.supportFragmentManager.beginTransaction().add(container.id, ScreenFragment()).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(seen.toString(), seen.contains("/ScreenFragment"))
        controller.pause().stop().destroy()
    }

    @Test
    fun theWireframeSourceForgetsItsWindowsWhenItStops() {
        val screen = RealScreen.open { a -> TextView(a).apply { text = "hello" } }
        val source = ViewFrameSource(WireframeRoots(), screen.activity)
        source.observe { }
        assertNotNull(source.capture(1_000L, null))
        val field = ViewFrameSource::class.java.getDeclaredField("lastRoots")
        field.isAccessible = true
        assertTrue("the capture saw the window", (field.get(source) as Array<*>).isNotEmpty())
        source.stopObserving()
        assertEquals("nothing keeps the activity's window while stopped", 0, (field.get(source) as Array<*>).size)
        screen.controller.pause().stop().destroy()
    }

    // ---- §14: exit reasons, hangs, logcat, flush ---------------------------------------

    private fun startWithState(dir: File, source: ExitSource, options: TracewayOptions = TracewayOptions()): TracewayClient =
        TracewayClient.initializeForTest(
            CONNECTION,
            options,
            TracewayTestOverrides(
                logExporter = logs, spanExporter = spans, http = http, scheduler = scheduler, random = { 0.5 },
                stateDirectory = dir, exitSource = source, sdkInt = 34, pid = 77,
            ),
        )

    @Test
    fun exitRecordsAndTracesThatCannotBeReadNeverReachTheApp() {
        val dir = tmp.newFolder("state")
        // A first start keeps the newest exit seen.
        startWithState(dir, ExitSource { emptyList() }).shutdown(2000)
        val endless = object : InputStream() {
            override fun read(): Int = 0x0A
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { java.util.Arrays.fill(b, off, off + len, 0x0A) }
        }
        val at = scheduler.nowMs + 10
        val hostile = listOf(
            ExitRecord(ExitReasons.REASON_CRASH_NATIVE, at, 1, null, null) { throw IOException("no tombstone") },
            ExitRecord(ExitReasons.REASON_CRASH_NATIVE, at + 1, -5, null, "garbage") { java.io.ByteArrayInputStream(ByteArray(300) { (it * 31).toByte() }) },
            ExitRecord(ExitReasons.REASON_CRASH_NATIVE, at + 2, 2, null, "endless") { endless },
            ExitRecord(ExitReasons.REASON_ANR, at + 3, 3, null, null) { throw OutOfMemoryError("trace") },
            ExitRecord(ExitReasons.REASON_ANR, at + 4, 4, null, "endless") { endless },
            ExitRecord(Int.MIN_VALUE, at + 5, 5, null, null),
        )
        scheduler.advance(1000)
        val c = startWithState(dir, ExitSource { hostile })
        c.captureException(RuntimeException("after"))
        c.flush(2000)
        assertTrue(exceptions().contains("after"))
        assertEquals("each still reported, from its description", 5, logs.all.count { r ->
            var type: Any? = null
            r.attributes.forEach { k, v -> if (k.key == "exception.type") type = v }
            type == "NativeCrash" || type == "ApplicationNotResponding"
        })
        c.shutdown(2000)

        // A source that fails as a whole: nothing reported, the SDK runs.
        scheduler.advance(1000)
        val failing = startWithState(dir, ExitSource { throw SecurityException("no exit info") })
        failing.captureException(RuntimeException("still"))
        failing.flush(2000)
        assertTrue(exceptions().contains("still"))
    }

    @Test
    fun hangHooksThatFailNeverReachTheMainThread() {
        val main = Handler(Looper.getMainLooper())
        val throwing = object : HangListener {
            override val thresholdMs: Long = 0
            override fun stalled(blockedMs: Long) = throw IllegalStateException("stalled")
            override fun ended(blockedMs: Long, report: Boolean) = throw OutOfMemoryError("ended")
        }
        var now = 0L
        var excusedCalls = 0
        val timers = ArrayList<() -> Unit>()
        val watchdog = HangWatchdog(
            clock = { now },
            postToMain = { main.post(it) },
            schedule = { _, task -> timers.add(task); Cancellable { } },
            thresholdMs = 0,
            intervalMs = 500,
            // Fine when the stall is told (which throws), failing when the hang ends.
            excused = { if (excusedCalls++ == 0) false else throw IllegalStateException("debugger check") },
            onHang = { throw IllegalStateException("metric") },
            listener = throwing,
        )
        watchdog.start()
        now = 3000
        timers.toList().forEach { it() }
        assertEquals("the stall was told", 1, excusedCalls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("and the end", 2, excusedCalls)
        watchdog.stop()

        // AppHangs whose stack, screen, session, file and report all fail.
        val notADirectory = tmp.newFile("hangs")
        val hangs = AppHangs(
            thresholdMs = 2000,
            directory = notADirectory,
            pid = 1,
            mainStack = { throw SecurityException("stack") },
            screen = { throw IllegalStateException("screen") },
            sessionId = { throw IllegalStateException("session") },
            now = { 0L },
            background = { it() },
            report = { throw OutOfMemoryError("report") },
        )
        hangs.stalled(2500)
        hangs.ended(3000, report = true)
        assertTrue(hangs.takePrevious().isEmpty())
        AppHangs(2000, notADirectory, 1, { null }, { emptyMap() }, { null }, { 0L }, { throw RejectedExecutionException("disk") }, { }).apply {
            stalled(2500)
            ended(2600, report = true)
        }
    }

    @Test
    fun theLogcatBridgeBehavesExactlyAsLogDoes() {
        val bridge = TracewayLogcat::class.java.declaredMethods.filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) }
        assertEquals(15, bridge.size)
        fun outcome(m: Method, args: Array<Any?>): String = try {
            "returned ${m.invoke(null, *args)}"
        } catch (e: InvocationTargetException) {
            "threw ${e.targetException.javaClass.name}"
        }
        fun same(phase: String) {
            for (m in bridge) {
                val logArgs = if (m.name == "println") m.parameterTypes.dropLast(1) else m.parameterTypes.toList()
                val real = android.util.Log::class.java.getMethod(m.name, *logArgs.toTypedArray())
                for (args in listOf(
                    logArgs.map { if (it == Integer.TYPE) android.util.Log.ERROR else null },
                    logArgs.map {
                        when (it) {
                            Integer.TYPE -> android.util.Log.WARN
                            String::class.java -> "text"
                            else -> IllegalStateException("tr")
                        }
                    },
                )) {
                    val bridgeArgs = if (m.name == "println") args + android.util.Log.VERBOSE else args
                    assertEquals("$phase ${m.name}${logArgs.map { it.simpleName }} $args", outcome(real, args.toTypedArray()), outcome(m, bridgeArgs.toTypedArray()))
                }
            }
        }
        same("before init")
        val c = start(TracewayOptions(collection = mapOf("signals" to mapOf("console" to mapOf("mode" to "stream", "minSeverity" to "trace")))))
        same("running")
        logs.failing = true
        same("with an exporter that fails")
        logs.failing = false
        c.flush(2000)
        assertTrue("the lines were recorded", logs.all.any { r ->
            var source: Any? = null
            r.attributes.forEach { k, v -> if (k.key == "log.source") source = v }
            source == "logcat"
        })
        c.shutdown(2000)
        same("after shutdown")
    }

    /** Its exporter's flush completes at [releaseAt] (epoch ms), from a thread of its own. */
    private class SlowLogs : LogRecordExporter {
        @Volatile
        var releaseAt = 0L

        override fun export(logs: Collection<LogRecordData>): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun flush(): CompletableResultCode {
            val wait = releaseAt - System.currentTimeMillis()
            if (wait <= 0) return CompletableResultCode.ofSuccess()
            val code = CompletableResultCode()
            Thread {
                Thread.sleep(wait)
                code.succeed()
            }.apply { isDaemon = true }.start()
            return code
        }

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    @Test
    fun aFlushOnTheMainThreadNeverWaitsLongEnoughForAnAnr() {
        val slow = SlowLogs()
        val c = TracewayClient.initializeForTest(
            CONNECTION,
            TracewayOptions(),
            TracewayTestOverrides(logExporter = slow, spanExporter = spans, http = http, scheduler = scheduler, random = { 0.5 }),
        )
        assertSame("Robolectric runs the test on the main thread", Looper.getMainLooper().thread, Thread.currentThread())
        slow.releaseAt = System.currentTimeMillis() + 6_000
        var began = System.nanoTime()
        Traceway.flush(10_000)
        val onMain = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertTrue("$onMain ms", onMain in 1_900L..3_500L)

        // Off the main thread the whole timeout is waited for.
        slow.releaseAt = System.currentTimeMillis() + 3_000
        var offMain = 0L
        Thread {
            began = System.nanoTime()
            c.flush(10_000)
            offMain = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        }.apply {
            start()
            join(20_000)
        }
        assertTrue("$offMain ms", offMain >= 2_600L)
    }

    // ---- the helper itself -----------------------------------------------------------

    @Test
    fun guardedContainsEverythingAndOnlyThat() {
        guarded("test") { throw OutOfMemoryError("ours") }
        assertEquals(3, guarded("test", 3) { throw IllegalStateException("ours") })
        assertEquals(4, guarded("test", 3) { 4 })
        guarded("test") { throw InterruptedException() }
        assertTrue("the interrupt is put back", Thread.interrupted())
        Guard.debug = true
        try {
            repeat(3) { guarded("logged once") { throw IllegalStateException("x") } }
        } finally {
            Guard.debug = false
        }
        // What runs in an interaction is the app's: it gets its own exception back.
        val error = IllegalStateException("app's")
        try {
            start().startInteraction("i").run { throw error }
            fail()
        } catch (e: IllegalStateException) {
            assertSame(error, e)
        }
    }
}
