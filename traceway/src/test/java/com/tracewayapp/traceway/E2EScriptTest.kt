package com.tracewayapp.traceway

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.button.MaterialButton
import com.tracewayapp.traceway.E2EScriptTest.CheckoutActivity
import com.tracewayapp.traceway.E2EScriptTest.GalleryActivity
import com.tracewayapp.traceway.E2EScriptTest.UserActivity
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import com.tracewayapp.traceway.wireframe.RealScreen
import com.tracewayapp.traceway.wireframe.TestActivity
import com.tracewayapp.traceway.wireframe.recomposingOnTestFrames
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Duration

/**
 * The scripted app of the end-to-end check, `tools/e2e/run.py`: the whole
 * SDK, started with `Traceway.init` and a connection string as an app
 * starts it, sending to a running Traceway. The screens are activities of
 * real views and one Compose screen under Robolectric; touches are
 * dispatched to their windows, so taps go through the SDK's touch tracking
 * and selectors and the screen through its wireframe recorder, and HTTP
 * calls go through the OkHttp interceptor to a local echo server.
 *
 * Runs only when the harness sets `TRACEWAY_E2E_DSN` and `E2E_CONTROL_DIR`.
 * Prints `E2E_PHASE <name> key=value…` at each phase and then waits for the
 * harness to create `<E2E_CONTROL_DIR>/<name>.go`, the screen drawing on
 * meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class E2EScriptTest {
    @After
    fun tearDown() = TracewayClient.resetForTest()

    @Test
    fun script() {
        val dsn = System.getenv("TRACEWAY_E2E_DSN")
        assumeTrue("the end-to-end script runs from tools/e2e/run.py", !dsn.isNullOrEmpty())
        val control = File(System.getenv("E2E_CONTROL_DIR") ?: error("E2E_CONTROL_DIR is not set"))
        E2EScript(dsn!!, control).main()
    }

    /** A checkout form: a name field and the pay button. */
    internal class CheckoutActivity : TestActivity() {
        lateinit var name: EditText
        lateinit var pay: MaterialButton
        var onPay: () -> Unit = {}

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            name = EditText(this).apply { id = android.R.id.edit; hint = "Name on card" }
            pay = MaterialButton(this).apply {
                id = android.R.id.button1
                text = "Pay now"
                setOnClickListener { onPay() }
            }
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    addView(Toolbar(this@CheckoutActivity).apply {
                        title = "Checkout"
                        setBackgroundColor(0xFF6750A4.toInt())
                        setTitleTextColor(Color.WHITE)
                    })
                    addView(TextView(this@CheckoutActivity).apply { text = "2 items · 30.59"; textSize = 18f; setPadding(32, 32, 32, 16) })
                    addView(name)
                    addView(pay)
                },
            )
        }
    }

    /** A user's page in Compose, as a Compose Navigation destination `/users/{id}` shows it. */
    internal class UserActivity : TestActivity() {
        var onOpenProfile: () -> Unit = {}

        /** Where the "Open profile" button is in the window, pixels: left, top, right, bottom. */
        var button: FloatArray? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(
                ComposeView(this).recomposingOnTestFrames().apply {
                    setContent {
                        MaterialTheme {
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                Text("Ada Lovelace", fontSize = 24.sp)
                                Text("User 42 · member since 2024", Modifier.padding(top = 8.dp, bottom = 24.dp))
                                Button(
                                    onClick = { onOpenProfile() },
                                    modifier = Modifier.testTag("open-profile").onGloballyPositioned {
                                        val r = it.boundsInWindow()
                                        button = floatArrayOf(r.left, r.top, r.right, r.bottom)
                                    },
                                ) { Text("Open profile") }
                            }
                        }
                    }
                },
            )
        }
    }

    /** A screen with a photo, for replay images. */
    internal class GalleryActivity : TestActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val photo = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 600f, 400f, 0xFFFF7043.toInt(), 0xFF283593.toInt(), Shader.TileMode.CLAMP) })
                canvas.drawCircle(450f, 120f, 70f, Paint().apply { color = 0xFFFFEE58.toInt(); isAntiAlias = true })
                canvas.drawRect(0f, 300f, 600f, 400f, Paint().apply { color = 0xFF1B5E20.toInt() })
                setHasAlpha(false)
            }
            setContentView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    addView(TextView(this@GalleryActivity).apply { text = "Gallery"; textSize = 22f; setPadding(32, 32, 32, 16) })
                    addView(
                        ImageView(this@GalleryActivity).apply {
                            setImageBitmap(photo)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                        LinearLayout.LayoutParams(MATCH_PARENT, (240 * resources.displayMetrics.density).toInt()),
                    )
                    addView(TextView(this@GalleryActivity).apply { text = "Sunset over the bay"; setPadding(32, 16, 32, 16) })
                },
            )
        }
    }
}

/** The lines of [tag] in Robolectric's log output, to [out]. */
private class TaggedLines(private val out: PrintStream, tag: String) : java.io.OutputStream() {
    private val marker = "/$tag:"
    private val line = java.io.ByteArrayOutputStream()

    override fun write(b: Int) {
        if (b != '\n'.code) {
            line.write(b)
            return
        }
        val text = line.toString(Charsets.UTF_8.name())
        line.reset()
        if (marker in text) out.println(text)
    }
}

/** The phases, in the order `tools/e2e/run.py` expects them. */
internal class E2EScript(private val dsn: String, private val control: File) {
    /** The process's stdout, past the SDK's capture of `System.out`: these lines are the harness's, not the app's. */
    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true)
    private val app: Application = RuntimeEnvironment.getApplication()
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private var lastPump = System.currentTimeMillis()
    private val canvases = HashMap<Long, Canvas>()
    private val echo = MockWebServer()
    private val http = OkHttpClient.Builder().addInterceptor(TracewayOkHttpInterceptor()).build()
    private var shown: ActivityController<out TestActivity>? = null
    private lateinit var client: TracewayClient

    class CheckoutException(amount: Int) : Exception("payment of $amount declined")

    class GalleryException(policy: String) : Exception("gallery failed with replay images $policy")

    companion object {
        const val JOURNEY_CAPTURE_SECONDS = 20

        /** The recording trigger of the app's own settings: a user's page, the profile button, the line it logs. */
        val journeyTrigger: Map<String, Any?> = mapOf(
            "id" to "e2e-open-profile",
            "name" to "Open profile",
            "cooldownSeconds" to 0,
            "maxPerSession" to 1,
            "steps" to listOf(
                mapOf("match" to mapOf("event" to mapOf("type" to "navigation", "value" to "/users/{id}"))),
                mapOf("match" to mapOf("event" to mapOf("type" to "click", "value" to "#open-profile")), "withinSeconds" to 30),
                mapOf("match" to mapOf("event" to mapOf("type" to "log", "value" to "Profile loaded", "mode" to "contains")), "withinSeconds" to 10),
            ),
            "actions" to listOf(
                mapOf("type" to "capture", "durationSeconds" to JOURNEY_CAPTURE_SECONDS, "lookbackSeconds" to 30, "replay" to true),
            ),
        )
    }

    // ---- harness ------------------------------------------------------------

    private fun phase(name: String, values: Map<String, Any?> = emptyMap(), wait: Boolean = true) {
        val extra = values.entries.sortedBy { it.key }.joinToString("") { " ${it.key}=${it.value}" }
        out.println("E2E_PHASE $name$extra")
        if (!wait) return
        val go = File(control, "$name.go")
        val deadline = System.currentTimeMillis() + 600_000
        while (!go.exists()) {
            check(System.currentTimeMillis() < deadline) { "the harness never let phase $name go on" }
            pump(200)
        }
    }

    private fun note(message: String) = out.println("E2E_NOTE $message")

    /**
     * Lets the app run for [ms] of wall time as a device would: the main
     * looper (layout, Compose frames, posted work) and a draw of every window
     * about every 30 ms, after which the windows' draw listeners run. The
     * looper's clock follows the wall clock.
     */
    private fun pump(ms: Long) {
        val end = System.currentTimeMillis() + ms
        do {
            val now = System.currentTimeMillis()
            val step = (now - lastPump).coerceIn(1, 100)
            lastPump = now
            mainLooper.idleFor(Duration.ofMillis(step))
            draw()
            Thread.sleep(15)
        } while (System.currentTimeMillis() < end)
    }

    private fun draw() {
        for (w in windows()) {
            if (w.width <= 0 || w.height <= 0) continue
            val canvas = canvases.getOrPut(w.width.toLong() shl 32 or w.height.toLong()) {
                Canvas(Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888))
            }
            try {
                w.draw(canvas)
            } catch (_: Throwable) {
            }
            if (w.viewTreeObserver.isAlive) w.viewTreeObserver.dispatchOnDraw()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val global = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(global) as List<View>).filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    }

    /** Shows activity [type] on top, as starting it does: the one before pauses, this one resumes, then the one before stops. */
    private fun <A : TestActivity> show(type: Class<A>): A {
        val previous = shown
        previous?.pause()
        val next = Robolectric.buildActivity(type).setup()
        previous?.stop()?.destroy()
        shown = next
        pump(800)
        return next.get()
    }

    /** A finger down and up at ([x], [y]) of the activity's window, pixels. */
    private fun tap(activity: TestActivity, x: Float, y: Float) {
        val window = activity.window
        val down = SystemClock.uptimeMillis()
        window.callback.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
        pump(80)
        window.callback.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN })
        pump(400)
    }

    private fun tap(activity: TestActivity, v: View) {
        val at = IntArray(2)
        v.getLocationInWindow(at)
        tap(activity, at[0] + v.width / 2f, at[1] + v.height / 2f)
    }

    /** Waits up to [seconds] for [done], asking the server for its settings every few seconds. */
    private fun untilConfigured(what: String, seconds: Int = 60, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var nextRefresh = 0L
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "the server's settings never reached the app: $what" }
            if (System.currentTimeMillis() >= nextRefresh) {
                Traceway.refreshConfig()
                nextRefresh = System.currentTimeMillis() + 3000
            }
            pump(200)
        }
        note("configured: $what")
    }

    private fun start(): TracewayClient {
        val c = Traceway.init(
            app,
            dsn,
            TracewayOptions(
                debug = true,
                version = "e2e-1.0.0",
                environment = "e2e",
                screenCapture = true,
                collection = mapOf("triggers" to listOf(journeyTrigger)),
                propagateTraceHeaderUrls = listOf(echo.url("/").toString()),
            ),
        ) ?: error("Traceway did not start")
        check(c.awaitReady(15_000)) { "the SDK did not start" }
        Traceway.setAttribute("user.id", "e2e-android-user")
        val deadline = System.currentTimeMillis() + 15_000
        while (c.currentSessionId == null && System.currentTimeMillis() < deadline) pump(100)
        pump(1500)
        return c
    }

    /** Stops the SDK and starts it again in the same process, as an app that calls `Traceway.shutdown` and `Traceway.init` does. */
    private fun restart(): TracewayClient {
        Traceway.shutdown(15_000)
        client = start()
        return client
    }

    private fun get(path: String): Int =
        http.newCall(Request.Builder().url(echo.url(path)).build()).execute().use { it.code }

    // ---- the app ------------------------------------------------------------

    fun main() {
        RealScreen.framesAt60Hz()
        // The SDK's debug log goes to the run's log, not into the app's console capture.
        ShadowLog.stream = PrintStream(TaggedLines(out, "Traceway"), true)
        echo.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("{\"method\":\"${request.method}\",\"path\":\"${request.path}\"}")
        }
        echo.start()
        try {
            run()
        } finally {
            echo.shutdown()
        }
    }

    private fun run() {
        client = start()
        phase("start", mapOf("instance" to client.getInstanceId(), "session" to client.currentSessionId, "echo" to echo.url("/")), wait = false)

        // 1. Breadcrumbs, taps included, stay on the device until something needs them.
        val checkout = show(CheckoutActivity::class.java)
        checkout.onPay = {
            Traceway.recordAction("checkout", "submit", mapOf("step" to 1))
            Traceway.track("e2e_event", mapOf("cart.items" to 3))
            println("Paying for 2 items")
            get("/v1/cart?id=42")
        }
        Traceway.log("e2e breadcrumb log")
        tap(checkout, checkout.name)
        for (ch in "Ada Lovelace") {
            checkout.name.append(ch.toString())
            pump(100)
        }
        tap(checkout, checkout.pay)
        pump(1500)
        phase("breadcrumbs_done")

        // 2. The exception brings them up, with a replay clip.
        Traceway.captureException(CheckoutException(1999))
        flushOffMain(15_000)
        phase("exception_sent")

        // 3. The trigger of the app's own settings: a user's page, a tap on
        // #open-profile and the line it logs start a capture that streams
        // logs and replay.
        val user = show(UserActivity::class.java)
        var opened = false
        user.onOpenProfile = {
            opened = true
            Traceway.log("Profile loaded for 42")
        }
        Traceway.recordNavigation("/users/42", "push", from = "/UserActivity", route = "/users/{id}")
        pump(500)
        val b = user.button ?: error("the profile button was never laid out")
        tap(user, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        check(opened) { "the tap did not reach the Compose button" }
        phase("journey_started", wait = false)
        for (i in 0 until JOURNEY_CAPTURE_SECONDS + 6) {
            Traceway.log("e2e journey tick $i")
            pump(1000)
        }
        Traceway.log("e2e after journey")
        flushOffMain(15_000)
        phase("journey_done")

        // 4. A capture the dashboard starts for this instance.
        for (i in 0 until 3) Traceway.log("e2e pre-capture tick $i")
        phase("await_capture")
        untilConfigured("the dashboard's capture") { client.settings.captures.isNotEmpty() }
        val captureId = client.settings.captures.first().id
        for (i in 0 until 12) {
            Traceway.log("e2e remote tick $i")
            pump(500)
        }
        flushOffMain(15_000)
        phase("capture_running", mapOf("capture" to captureId))
        untilConfigured("the capture's cancellation") { client.settings.captures.isEmpty() }
        pump(1000)
        Traceway.log("e2e after cancel")
        flushOffMain(15_000)
        phase("capture_cancelled")

        // 5. A Data Collection rule whose click step is the selector the
        // server recorded for the pay tap matches the next tap and records.
        untilConfigured("the rule from the recorded selector") { client.settings.triggers.any { it.id == "e2e-remote-pay" } }
        val again = show(CheckoutActivity::class.java)
        again.onPay = { Traceway.log("e2e paid again") }
        tap(again, again.pay)
        pump(1000)
        flushOffMain(15_000)
        phase("rule_tapped")

        // 6. Replay images: a rule turns them off, then on. The policy a
        // session reports is the one at its start, so the app restarts after
        // each change and continues the session.
        for (policy in listOf(TracewayImagePolicy.OFF, TracewayImagePolicy.ALWAYS)) {
            untilConfigured("replay.images ${policy.wire}") { client.settings.replayImages == policy }
            restart()
            show(GalleryActivity::class.java)
            pump(4000)
            Traceway.captureException(GalleryException(policy.wire))
            flushOffMain(15_000)
            phase("images_${policy.wire}", mapOf("session" to client.currentSessionId, "images" to client.settings.replayImages.wire))
        }

        // 7. Metrics: the app's own next to what the SDK measured.
        Traceway.metric("e2e.checkout.amount", 30.59, mapOf("method" to "card"))
        Traceway.count("e2e.checkout.completed", 2.0)
        flushOffMain(15_000)
        phase("metrics_sent")

        val session = client.currentSessionId
        Traceway.shutdown(15_000)
        phase("done", mapOf("session" to session), wait = false)
    }

    /** flush() waits 2 s at most on the main thread (an ANR comes at 5 s): the script waits for the whole send, as an app's worker would. */
    private fun flushOffMain(timeoutMs: Long) {
        Thread { Traceway.flush(timeoutMs) }.apply {
            start()
            join(timeoutMs + 5_000)
        }
    }
}
