package com.tracewayapp.traceway.wireframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.tracewayapp.traceway.TracewayMasks
import com.tracewayapp.traceway.core.applyOverlay
import com.tracewayapp.traceway.core.ceilingsFrom
import com.tracewayapp.traceway.core.clampToCeilings
import com.tracewayapp.traceway.core.defaultSettings
import com.tracewayapp.traceway.core.parseSettingsDocument
import com.tracewayapp.traceway.compose.TracewayMaskKey
import com.tracewayapp.traceway.interaction.TouchTracker
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wireframe replay gallery (the iOS SDK's `WireframeGalleryTests`, the
 * same scenarios): real screens of Android views and one of Jetpack
 * Compose, shown in activity windows under Robolectric and recorded by the
 * real [WireframeRecorder] on its clock while each scenario taps, types,
 * scrolls, opens dialogs, sheets and menus, and pushes screens. Touches go
 * through the windows' callbacks, where the SDK's [TouchTracker] sees them.
 *
 * At every checkpoint the windows draw their own picture (the dp-sized
 * image of what Android drew) and the frames of the texts on screen are
 * taken from the views ([GalleryDriver.boxes]). The test then checks the
 * recording the way rrweb's replayer needs it: a Meta event and a full
 * snapshot first, time order, every mutation applying through the strict
 * [Mirror], the mirror equal to every captured frame at that frame's time;
 * each checkpoint's replay shows the texts it expects and none it hides;
 * no secret appears anywhere.
 *
 * It writes `events.json`, `run.json`, `app/<checkpoint>.png` and
 * `tree/<checkpoint>.txt` to `WIREFRAME_GALLERY_OUT` (default
 * `build/wireframe-gallery` in the module), the files
 * `tools/wireframe/run.py` plays in rrweb-player and compares with the app's
 * pictures. `WIREFRAME_GALLERY_ONLY=a,b` runs only those scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WireframeGalleryTest {
    @Test
    fun gallery() {
        val only = System.getenv("WIREFRAME_GALLERY_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val scenarios = GalleryScenes.all.filter { only == null || it.id in only }
        assertTrue("WIREFRAME_GALLERY_ONLY=$only names no scenario of ${GalleryScenes.all.map { it.id }}", scenarios.isNotEmpty())
        // Colours other tests resolved in this JVM must not be served to the gallery's screens.
        resetAndroidxColorCaches()
        val driver = GalleryDriver(GalleryDriver.outputDirectory())
        try {
            for (scenario in scenarios) driver.run(scenario)
            val events = driver.finish()
            driver.check(events)
        } finally {
            driver.close()
        }
    }
}

/** One screen of the gallery and the steps that take it through its states. */
internal class GalleryScenario(val id: String, val script: GalleryDriver.() -> Unit)

internal class GalleryCheckpoint(
    val name: String,
    val scenario: String,
    val at: Long,
    val shows: List<String>,
    val hides: List<String>,
    /** Areas left out of the picture comparison (x, y, width, height in dp), such as a spinner. */
    val ignore: List<RectF>,
    val minMutations: Int,
    val boxes: List<GalleryBox>,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "scenario" to scenario,
        "at" to at,
        "shows" to shows,
        "hides" to hides,
        "ignore" to ignore.map { listOf(r2(it.left), r2(it.top), r2(it.width()), r2(it.height())) },
        "minMutations" to minMutations,
        "boxes" to boxes.map { linkedMapOf("text" to it.text, "x" to r2(it.x), "y" to r2(it.y), "w" to r2(it.w), "h" to r2(it.h)) },
    )
}

/** Where a text is on the screen, in dp from its top-left (the box around it when transformed). */
internal class GalleryBox(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

private fun r2(v: Float): Double = Math.round(v * 100.0) / 100.0

/** An activity of the gallery, in the Material 3 theme. */
internal open class GalleryActivity : AppCompatActivity() {
    protected open val themeId: Int = com.google.android.material.R.style.Theme_Material3_Light_NoActionBar

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeId)
        super.onCreate(savedInstanceState)
    }
}

/** The Material 3 day-night theme; dark when the gallery runs it with the night qualifier. */
internal class GalleryDayNightActivity : GalleryActivity() {
    override val themeId: Int = com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar

    /**
     * The mode switched the way an app switches it, for this activity
     * alone: AppCompat gives it a configuration (and so resources) of its
     * own, while the process keeps its qualifiers. Changing Robolectric's
     * qualifiers in place instead let AndroidX's process-wide
     * ColorStateList cache serve colours resolved in the other mode.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        delegate.localNightMode = if (night) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        }
        super.attachBaseContext(newBase)
    }

    companion object {
        /** The mode the next one opens in. */
        @JvmStatic
        var night = false
    }
}

/**
 * Shows the scenarios one activity at a time, records their windows, drives
 * them with touches and keys, and takes the checkpoints. Two clocks move
 * together: Robolectric's (the looper, animations, event times) and the
 * recorder's ([scheduler], the rrweb timestamps).
 */
internal class GalleryDriver(private val outDir: File) {
    val scheduler = ManualScheduler(START_MS)
    private val main = ManualMainThread(scheduler)
    private val app = RuntimeEnvironment.getApplication()
    private val views = ViewFrameSource(WireframeRoots(), app, mainThread = main)
    private val frames = ArrayList<WireframeFrame>()
    private var captures = 0
    private val drawn = HashMap<View, Bitmap>()
    private val source = object : WireframeFrameSource by views {
        override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
            drawWindows() // as a device draws before the capture runs
            captures++
            return views.capture(timestampMs, href)?.also { frames.add(it) }
        }
    }
    val recorder = WireframeRecorder(100_000, FPS, source, main, scheduler::now)
    private val touches = TouchTracker()
    private val roots = WireframeRoots()

    /** The capture inside [views], for what it still encodes. */
    private val capture: WireframeCapture =
        ViewFrameSource::class.java.getDeclaredField("capture").apply { isAccessible = true }.get(views) as WireframeCapture

    var scenario = ""
        private set
    val checkpoints = ArrayList<GalleryCheckpoint>()
    val secrets = LinkedHashSet<String>()
    private val failures = ArrayList<String>()
    private val starts = ArrayList<Pair<String, Long>>()
    private val names = HashSet<String>()
    private val stack = ArrayList<ActivityController<out GalleryActivity>>()
    private val stopped = HashSet<ActivityController<out GalleryActivity>>()
    private val windowsOf = HashMap<View, Window>()

    /** Taps the recording must hold: a touch end at this point (dp), from the scenario. */
    private val expectedTaps = ArrayList<Triple<String, Int, Int>>()
    private var pointerDown = 0L
    private var keyboardDp = 0f

    val activity: GalleryActivity get() = stack.last().get()
    val density: Float get() = app.resources.displayMetrics.density

    init {
        RealScreen.framesAt60Hz()
        recorder.screenName = { stack.lastOrNull()?.get()?.let { "/" + it.javaClass.simpleName } }
        touches.addPointerListener(recorder)
        outDir.deleteRecursively()
        File(outDir, "app").mkdirs()
        File(outDir, "tree").mkdirs()
        policy("{}")
        recorder.start()
    }

    fun close() {
        recorder.stop()
        for (c in stack.reversed()) destroy(c)
        stack.clear()
        RuntimeEnvironment.setQualifiers("+notnight")
    }

    fun run(s: GalleryScenario) {
        scenario = s.id
        starts.add(s.id to scheduler.now())
        println("GALLERY_SCENARIO ${s.id}")
        policy("{}")
        try {
            s.script(this)
        } catch (t: Throwable) {
            failures.add("${s.id}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace()
        }
        hideKeyboard()
    }

    /** Applies a local collection document (`TracewayOptions.collection`) to what the recorder records. */
    fun policy(collection: String) {
        val settings = clampToCeilings(applyOverlay(defaultSettings(), parseSettingsDocument(collection)), ceilingsFrom(screenCapture = true))
        views.images.policy = settings.replayImages
        views.images.unmetered = { false }
        recorder.maskAllInputs = settings.replayMaskAllInputs
    }

    // ---- screens --------------------------------------------------------------------

    /** A new screen: [content] in a new activity of [cls], in place of the screens shown before. */
    fun <A : GalleryActivity> open(cls: Class<A>, content: (A) -> View): A {
        val controller = Robolectric.buildActivity(cls).setup()
        val a = controller.get()
        a.setContentView(content(a))
        track(a.window)
        for (old in stack.reversed()) destroy(old)
        stack.clear()
        stack.add(controller)
        settle()
        return a
    }

    fun open(content: (GalleryActivity) -> View): GalleryActivity = open(GalleryActivity::class.java, content)

    /** A screen pushed over the one shown: the one below stops, as Android stops an activity under another. */
    fun push(content: (GalleryActivity) -> View): GalleryActivity {
        val below = stack.last()
        val controller = Robolectric.buildActivity(GalleryActivity::class.java).setup()
        val a = controller.get()
        a.setContentView(content(a))
        track(a.window)
        below.pause().stop()
        stopped.add(below)
        below.get().window.decorView.visibility = View.INVISIBLE
        stack.add(controller)
        settle()
        return a
    }

    /** The screen on top finishes; the one below comes back. */
    fun pop() {
        val top = stack.removeAt(stack.size - 1)
        destroy(top)
        val below = stack.last()
        below.get().window.decorView.visibility = View.VISIBLE
        // restart() starts it again.
        below.restart().resume()
        stopped.remove(below)
        settle()
    }

    private fun destroy(c: ActivityController<out GalleryActivity>) {
        try {
            val a = c.get()
            windowsOf.remove(a.window.decorView)
            if (!a.isFinishing) a.finish()
            if (stopped.remove(c)) c.destroy() else c.pause().stop().destroy()
        } catch (t: Throwable) {
            failures.add("$scenario: closing a screen: $t")
        }
    }

    /** Touches of [window] (a dialog's) are seen by the SDK and go to it. */
    fun track(window: Window) {
        touches.track(window)
        windowsOf[window.decorView] = window
    }

    // ---- time ------------------------------------------------------------------------

    /** Lets [ms] pass: the app runs its frames (layout, animations, posted work) and the recorder its timer. */
    fun elapse(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = min(FRAME_MS, left)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            // The app drew a frame; the recorder captures after draws, at most FPS a second.
            recorder.onDraw()
            scheduler.advance(step)
            left -= step
        }
    }

    /** Lets the screen come to rest, and the icon glyphs and pictures it shows be encoded. */
    fun settle(ms: Long = 400) {
        elapse(ms)
        val deadline = System.currentTimeMillis() + 5000
        var waited = false
        while (encoding() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            elapse(FRAME_MS)
            waited = true
        }
        if (waited) elapse(200)
    }

    private fun encoding(): Boolean =
        capture.glyphs.all.any { it.css == null } || capture.images.all.any { it.css == null && !it.failed }

    // ---- checkpoints ------------------------------------------------------------------

    /**
     * Captures the screen as it is now, the windows' own picture of it and
     * where its texts are. Nothing changes for [SAMPLE_MS] after it, the
     * moment the checker looks at the replay.
     */
    fun checkpoint(
        state: String = "",
        shows: List<String> = emptyList(),
        hides: List<String> = emptyList(),
        ignore: List<RectF> = emptyList(),
        minMutations: Int = 0,
        settle: Long = 400,
        unboxed: Set<String> = emptySet(),
    ) {
        val name = if (state.isEmpty()) scenario else "$scenario-$state"
        if (!names.add(name)) failures.add("checkpoint $name twice")
        if (settle > 0) settle(settle)
        val before = captures
        var n = 0
        recorder.onDraw()
        while (captures == before && n++ < 20) elapse(FRAME_MS)
        val at = scheduler.now()
        val windows = drawWindows()
        picture(name, windows)
        tree(name, windows)
        val ignored = ArrayList(ignore)
        if (keyboardDp > 0f) ignored.add(keyboardRect())
        val boxes = boxes(windows).filter { it.text !in unboxed }
        checkpoints.add(GalleryCheckpoint(name, scenario, at, shows, hides, ignored, minMutations, boxes))
        println("GALLERY_CHECKPOINT $name $at")
        elapse(SAMPLE_MS + 200)
    }

    /** Text the app shows that must never reach the recording. */
    fun secret(text: String) {
        secrets.add(text)
    }

    fun expect(condition: Boolean, what: String) {
        if (!condition) failures.add("$scenario: $what")
    }

    // ---- touches -----------------------------------------------------------------------

    /** Where [v]'s middle is on the screen, in pixels. */
    fun centerOf(v: View): Pair<Float, Float> {
        val r = onScreenPx(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()))
        return r.centerX() to r.centerY()
    }

    /** A finger taps the middle of [v]. */
    fun tap(v: View) {
        val (x, y) = centerOf(v)
        tapAt(x, y)
    }

    /** A finger taps the screen at ([x], [y]) pixels; the window there gets the touches. */
    fun tapAt(x: Float, y: Float) {
        val target = windowAt(x, y)
        pointerDown = SystemClock.uptimeMillis()
        touch(target, MotionEvent.ACTION_DOWN, x, y)
        elapse(70)
        touch(target, MotionEvent.ACTION_UP, x, y)
        if (target.second != null) expectedTaps.add(Triple(scenario, (x / density).roundToInt(), (y / density).roundToInt()))
        elapse(180)
    }

    /**
     * A finger drags from ([x0], [y0]) to ([x1], [y1]) pixels over [ms], then
     * holds still before it lifts, so the content does not fling.
     */
    fun drag(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long = 480) {
        val target = windowAt(x0, y0)
        pointerDown = SystemClock.uptimeMillis()
        touch(target, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = max(2, (ms / FRAME_MS).toInt())
        for (i in 1..steps) {
            elapse(FRAME_MS)
            val f = i.toFloat() / steps
            touch(target, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
        }
        repeat(4) {
            elapse(40)
            touch(target, MotionEvent.ACTION_MOVE, x1, y1)
        }
        touch(target, MotionEvent.ACTION_UP, x1, y1)
        elapse(250)
    }

    /**
     * Drags the content of [v] by ([dxDp], [dyDp]), positive forward (up or
     * to the left), in strokes that stay inside it.
     */
    fun scroll(v: View, dxDp: Float = 0f, dyDp: Float = 0f, ms: Long = 480) {
        val frame = onScreenPx(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()))
        val maxX = frame.width() * 0.7f / density
        val maxY = frame.height() * 0.7f / density
        val slop = 12f * density
        var leftX = dxDp
        var leftY = dyDp
        while (Math.abs(leftX) > 0.5f || Math.abs(leftY) > 0.5f) {
            val sx = leftX.coerceIn(-maxX, maxX)
            val sy = leftY.coerceIn(-maxY, maxY)
            val dx = sx * density + Math.signum(sx) * slop
            val dy = sy * density + Math.signum(sy) * slop
            drag(frame.centerX() + dx / 2, frame.centerY() + dy / 2, frame.centerX() - dx / 2, frame.centerY() - dy / 2, ms)
            leftX -= sx
            leftY -= sy
        }
    }

    /** Types [text] into [field] one key at a time, a frame or so after each. */
    fun type(field: EditText, text: String) {
        if (!field.isFocused) tap(field)
        for (ch in text) {
            field.append(ch.toString())
            elapse(120)
        }
    }

    private fun windowAt(x: Float, y: Float): Pair<WireframeRoots.Root, Window?> {
        val list = ArrayList<WireframeRoots.Root>()
        roots.current(list)
        val root = list.lastOrNull { x >= it.x && y >= it.y && x < it.x + it.view.width && y < it.y + it.view.height }
            ?: list.last()
        return root to windowsOf[root.view]
    }

    private fun touch(target: Pair<WireframeRoots.Root, Window?>, action: Int, x: Float, y: Float) {
        val (root, window) = target
        val e = MotionEvent.obtain(pointerDown, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        // The window's coordinates; the raw ones stay the screen's.
        e.offsetLocation(-root.x.toFloat(), -root.y.toFloat())
        if (window != null) window.callback.dispatchTouchEvent(e) else root.view.dispatchTouchEvent(e)
        e.recycle()
    }

    // ---- the keyboard -------------------------------------------------------------------

    /** Shows the keyboard over the activity's window: an IME inset at its bottom, as the system gives it. */
    fun showKeyboard(heightDp: Float) {
        insets(activity.window.decorView, heightDp)
        keyboardDp = heightDp
        settle()
    }

    fun hideKeyboard() {
        if (keyboardDp == 0f) return
        keyboardDp = 0f
        if (stack.isNotEmpty()) insets(activity.window.decorView, null)
        settle()
    }

    private fun keyboardRect(): RectF {
        val decor = activity.window.decorView
        val h = decor.height / density
        return RectF(0f, h - keyboardDp, decor.width / density, h)
    }

    private fun insets(decor: View, heightDp: Float?) {
        val insets = if (heightDp == null) {
            WindowInsets.Builder().build()
        } else {
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, (heightDp * density).roundToInt()))
                .setVisible(WindowInsets.Type.ime(), true)
                .build()
        }
        val root = View::class.java.getMethod("getViewRootImpl").invoke(decor)!!
        root.javaClass.getDeclaredField("mLastWindowInsets").apply { isAccessible = true }.set(root, insets)
    }

    // ---- pictures, trees, boxes -------------------------------------------------------

    /** The windows on screen, bottom first, each drawn into its bitmap. */
    private fun drawWindows(): List<WireframeRoots.Root> {
        val list = ArrayList<WireframeRoots.Root>()
        roots.current(list)
        for (w in list) {
            val v = w.view
            var bitmap = drawn[v]
            if (bitmap == null || bitmap.width != v.width || bitmap.height != v.height) {
                bitmap = Bitmap.createBitmap(max(1, v.width), max(1, v.height), Bitmap.Config.ARGB_8888)
                drawn[v] = bitmap
            }
            bitmap!!.eraseColor(Color.TRANSPARENT)
            v.draw(Canvas(bitmap))
        }
        drawn.keys.retainAll(list.map { it.view }.toSet())
        return list
    }

    /** `app/<name>.png`: the windows composed as the screen shows them, at one pixel per dp. */
    private fun picture(name: String, windows: List<WireframeRoots.Root>) {
        if (windows.isEmpty()) {
            failures.add("$name: nothing on screen")
            return
        }
        val base = windows.firstOrNull { it.type in 1..99 } ?: windows.first()
        val w = base.x + base.view.width
        val h = base.y + base.view.height
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (r in windows) {
            if (r.dim > 0f) canvas.drawColor(Color.argb((r.dim * 255).roundToInt(), 0, 0, 0))
            drawn[r.view]?.let { canvas.drawBitmap(it, r.x.toFloat(), r.y.toFloat(), paint) }
        }
        val scaled = Bitmap.createScaledBitmap(full, (w / density).roundToInt(), (h / density).roundToInt(), true)
        File(outDir, "app/$name.png").outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        full.recycle()
        scaled.recycle()
    }

    /** [r] in [v]'s own pixels, on the screen: the box around it after every transform above it. */
    fun onScreenPx(v: View, r: RectF): RectF {
        val p = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        var cur: View = v
        while (true) {
            val parent = cur.parent as? View
            if (parent == null) {
                val at = IntArray(2)
                cur.getLocationOnScreen(at)
                for (i in 0 until 4) {
                    p[2 * i] += at[0]
                    p[2 * i + 1] += at[1]
                }
                break
            }
            if (!cur.matrix.isIdentity) cur.matrix.mapPoints(p)
            for (i in 0 until 4) {
                p[2 * i] += (cur.left - parent.scrollX).toFloat()
                p[2 * i + 1] += (cur.top - parent.scrollY).toFloat()
            }
            cur = parent
        }
        return RectF(
            min(min(p[0], p[2]), min(p[4], p[6])), min(min(p[1], p[3]), min(p[5], p[7])),
            max(max(p[0], p[2]), max(p[4], p[6])), max(max(p[1], p[3]), max(p[5], p[7])),
        )
    }

    private fun toDp(r: RectF): RectF = RectF(r.left / density, r.top / density, r.right / density, r.bottom / density)

    /** Whether [v] and every view above it are visible and not transparent. */
    private fun shown(v: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur.visibility != View.VISIBLE || cur.alpha <= 0.002f) return false
            cur = cur.parent as? View
        }
        return true
    }

    /**
     * The frames of the texts on screen that the replay shows as they are
     * (not masked, not typed into a field), each where Android draws it: a
     * TextView's text layout, a Compose text's node. Texts shown twice are
     * left out, as they cannot be told apart.
     */
    private fun boxes(windows: List<WireframeRoots.Root>): List<GalleryBox> {
        val found = ArrayList<GalleryBox>()
        val visible = android.graphics.Rect()
        fun walk(v: View) {
            if (!shown(v)) return
            if (TracewayMasks.effective(v) != null) return
            if (v is ViewRootForTest) {
                composeBoxes(v, found)
                return
            }
            if (v is TextView && v.width > 0 && v.height > 0 && v.getGlobalVisibleRect(visible)) {
                textBox(v)?.let { found.add(it) }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        for (w in windows) walk(w.view)
        val counts = found.groupingBy { it.text }.eachCount()
        return found.filter { counts[it.text] == 1 && it.text.isNotBlank() && secrets.none { s -> it.text.contains(s) } }
    }

    private fun textBox(v: TextView): GalleryBox? {
        val layout = v.layout ?: return null
        if (v is EditText) {
            // Typed text is masked; a Material field's hint is the layout's label.
            if (v.text.isNotEmpty() || v.hint.isNullOrEmpty()) return null
            if (v.javaClass.name.endsWith("TextInputEditText")) return null
        } else if (v.text.isNullOrEmpty() || WireframeCapture.isPassword(v)) {
            return null
        }
        val shown = layout.text.toString().replace("﻿", "")
        if (shown.isBlank()) return null
        val l = v.totalPaddingLeft.toFloat()
        val t = v.totalPaddingTop.toFloat()
        val r = (v.width - v.totalPaddingRight).toFloat()
        val box = toDp(onScreenPx(v, RectF(l, t, max(l, r), t + layout.height)))
        return GalleryBox(shown, box.left, box.top, box.width(), box.height())
    }

    private fun composeBoxes(v: View, out: ArrayList<GalleryBox>) {
        val root = (v as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
        val at = IntArray(2)
        v.rootView.getLocationOnScreen(at)
        fun walk(n: SemanticsNode, masked: Boolean) {
            val config = n.config
            val tag = config.getOrNull(SemanticsProperties.TestTag)
            val m = masked || config.getOrNull(TracewayMaskKey) != null || tag == TracewayMasks.TAG_BLUR || tag == TracewayMasks.TAG_BLANK
            val text = config.getOrNull(SemanticsProperties.Text)
            if (!m && config.getOrNull(SemanticsProperties.EditableText) == null && !text.isNullOrEmpty() &&
                n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f
            ) {
                val p = n.positionInWindow
                val s = n.size
                out.add(
                    GalleryBox(
                        text.joinToString(" "), (p.x + at[0]) / density, (p.y + at[1]) / density,
                        s.width / density, s.height / density,
                    ),
                )
            }
            for (c in n.children) walk(c, m)
        }
        walk(root, false)
    }

    /** `tree/<name>.txt`: every window and view with its class, text and frame on screen (dp), for diagnosis. */
    private fun tree(name: String, windows: List<WireframeRoots.Root>) {
        val lines = ArrayList<String>()
        fun color(c: Int): String = cssColor(c)
        fun line(depth: Int, v: View) {
            if (lines.size > 6000 || depth > 80) return
            val r = toDp(onScreenPx(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())))
            val b = StringBuilder(" ".repeat(depth))
            b.append(v.javaClass.name).append(" view=").append(v.javaClass.simpleName.ifEmpty { "View" })
            if (v is TextView) b.append(" text=\"").append(v.text.toString().replace("\n", "\\n").take(80)).append('"')
            b.append(String.format(java.util.Locale.US, " frame=(%.1f,%.1f %.1fx%.1f)", r.left, r.top, r.width(), r.height()))
            if (v.id != View.NO_ID) b.append(" id=").append(runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault(v.id.toString()))
            if (v.visibility == View.INVISIBLE) b.append(" invisible")
            if (v.visibility == View.GONE) b.append(" gone")
            if (v.alpha < 1f) b.append(String.format(java.util.Locale.US, " alpha=%.2f", v.alpha))
            if (!v.matrix.isIdentity) b.append(" transform rotation=${v.rotation} scale=${v.scaleX},${v.scaleY} pivot=${v.pivotX},${v.pivotY}")
            if (v.scrollX != 0 || v.scrollY != 0) b.append(" scroll=${v.scrollX},${v.scrollY}")
            if (v.elevation != 0f || v.translationZ != 0f) b.append(" z=${v.z / density}")
            v.background?.let { d ->
                b.append(" bg=").append(d.javaClass.simpleName)
                if (d is android.graphics.drawable.ColorDrawable) b.append('(').append(color(d.color)).append(')')
            }
            if (v.clipToOutline) b.append(" clipToOutline")
            if (v is ViewGroup && !v.clipChildren) b.append(" noClipChildren")
            if (v is TextView) b.append(" color=").append(color(v.currentTextColor)).append(" size=").append(v.textSize / density)
            TracewayMasks.own(v)?.let { b.append(" mask=").append(it.javaClass.simpleName) }
            lines.add(b.toString())
            if (v is ViewRootForTest) {
                semanticsLines(v.semanticsOwner.unmergedRootSemanticsNode, depth + 1, lines, v)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) line(depth + 1, v.getChildAt(i))
        }
        for (w in windows) {
            lines.add("window type=${w.type} at=(${w.x / density},${w.y / density}) dim=${w.dim} ime=${w.ime / density}")
            line(1, w.view)
        }
        File(outDir, "tree/$name.txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun semanticsLines(n: SemanticsNode, depth: Int, lines: ArrayList<String>, view: View) {
        if (lines.size > 6000 || depth > 120) return
        val at = IntArray(2)
        view.rootView.getLocationOnScreen(at)
        val p = n.positionInWindow
        val s = n.size
        val b = StringBuilder(" ".repeat(depth)).append("SemanticsNode view=Compose#").append(n.id)
        n.config.getOrNull(SemanticsProperties.Text)?.let { b.append(" text=\"").append(it.joinToString(" ").take(80)).append('"') }
        b.append(
            String.format(
                java.util.Locale.US, " frame=(%.1f,%.1f %.1fx%.1f)",
                (p.x + at[0]) / density, (p.y + at[1]) / density, s.width / density, s.height / density,
            ),
        )
        for ((key, value) in n.config) {
            if (key.name == "Text") continue
            b.append(' ').append(key.name).append('=').append(value.toString().take(40))
        }
        lines.add(b.toString())
        for (c in n.children) semanticsLines(c, depth + 1, lines, view)
    }

    // ---- the recording ----------------------------------------------------------------

    /** The recording of the whole run, as the SDK uploads it, written with `run.json`. */
    fun finish(): List<Map<String, Any?>> {
        elapse(500)
        val clip = recorder.captureRecording("gallery", null, scheduler.now())
            ?: error("the gallery recorded nothing")
        @Suppress("UNCHECKED_CAST")
        val events = Json.parse(Json.stringify(clip.events)) as List<Map<String, Any?>>
        File(outDir, "events.json").writeText(Json.stringify(events))
        val width = frames.firstOrNull()?.width ?: 0f
        val height = frames.firstOrNull()?.height ?: 0f
        val run = linkedMapOf<String, Any?>(
            "platform" to "android",
            "device" to "Robolectric ${Build.MODEL}",
            "os" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "width" to width.roundToInt(),
            "height" to height.roundToInt(),
            "density" to density.toDouble(),
            "startedAt" to START_MS,
            "sampleMs" to SAMPLE_MS,
            "secrets" to secrets.toList(),
            "scenarios" to starts.map { linkedMapOf("id" to it.first, "at" to it.second) },
            "checkpoints" to checkpoints.map { it.json() },
        )
        File(outDir, "run.json").writeText(Json.stringify(run))
        println("GALLERY_OUT ${outDir.absolutePath} (${checkpoints.size} checkpoints, ${events.size} events)")
        return events
    }

    /**
     * The recording rebuilds the screen exactly: through the strict mirror,
     * at every frame's time the DOM is that frame's, and at every
     * checkpoint the replay shows what it should; no secret anywhere.
     */
    fun check(events: List<Map<String, Any?>>) {
        fun type(e: Map<String, Any?>) = (e["type"] as Number).toInt()
        fun time(e: Map<String, Any?>) = (e["timestamp"] as Number).toLong()

        @Suppress("UNCHECKED_CAST")
        fun data(e: Map<String, Any?>) = e["data"] as Map<String, Any?>
        assertEquals("a meta event and a full snapshot first", listOf(4, 2), events.take(2).map(::type))
        val stamps = events.map(::time)
        assertEquals("events in time order", stamps.sorted(), stamps)

        val mirror = Mirror()
        var next = 0
        fun playUntil(moment: Long, inclusive: Boolean) {
            while (next < events.size && (time(events[next]) < moment || (inclusive && time(events[next]) == moment))) {
                val e = events[next++]
                when (type(e)) {
                    2 -> {
                        @Suppress("UNCHECKED_CAST")
                        mirror.fullSnapshot(data(e)["node"] as Map<String, Any?>)
                    }
                    3 -> {
                        val d = data(e)
                        when ((d["source"] as Number).toInt()) {
                            0 -> mirror.mutation(d)
                            2 -> assertTrue("a touch on a node not on the page", mirror.byId.containsKey((d["id"] as Number).toInt()))
                        }
                    }
                }
            }
        }
        // Frames (at their time, inclusive) and checkpoints (the moment the replay is looked at, exclusive), in order.
        class Moment(val at: Long, val frame: WireframeFrame?, val checkpoint: GalleryCheckpoint?)
        val moments = ArrayList<Moment>()
        for (f in frames) moments.add(Moment(f.timestampMs, f, null))
        for (cp in checkpoints) moments.add(Moment(cp.at + SAMPLE_MS, null, cp))
        moments.sortWith(compareBy<Moment>({ it.at }, { if (it.frame != null) 1 else 0 }))
        val mutationTimes = events.filter { type(it) == 3 && (data(it)["source"] as Number).toInt() == 0 }.map(::time)
        var previous = Long.MIN_VALUE
        for (m in moments) {
            val frame = m.frame
            if (frame != null) {
                playUntil(m.at, inclusive = true)
                val screen = frame.screen ?: continue
                if (dumpVNode(screen) != mirror.screen.dump()) {
                    fail("the replay at ${frame.timestampMs} is not the frame captured then:\n${dumpVNode(screen).take(3000)}\nreplayed:\n${mirror.screen.dump().take(3000)}")
                }
                continue
            }
            val cp = m.checkpoint!!
            playUntil(m.at, inclusive = false)
            val texts = mirror.texts().joinToString("\n")
            for (s in cp.shows) if (!texts.contains(s)) failures.add("${cp.name}: the replay does not show \"$s\"")
            for (h in cp.hides) if (texts.contains(h)) failures.add("${cp.name}: the replay shows \"$h\", which it must hide")
            val mutations = mutationTimes.count { it > previous && it <= cp.at }
            if (mutations < cp.minMutations) failures.add("${cp.name}: $mutations mutations since the checkpoint before, wanted ${cp.minMutations}")
            previous = cp.at
        }
        val all = Json.stringify(events)
        for (s in secrets) if (all.contains(s)) failures.add("the recording holds the secret \"$s\"")
        val ends = events.filter { type(it) == 3 && (data(it)["source"] as Number).toInt() == 2 && (data(it)["type"] as Number).toInt() == 9 }
            .map { (data(it)["x"] as Number).toInt() to (data(it)["y"] as Number).toInt() }.toSet()
        for ((sid, x, y) in expectedTaps) {
            if (ends.none { Math.abs(it.first - x) <= 1 && Math.abs(it.second - y) <= 1 }) failures.add("$sid: no touch end recorded at ($x, $y)")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    companion object {
        const val START_MS = 1_767_268_800_000L
        const val SAMPLE_MS = 150L
        const val FPS = 10
        const val FRAME_MS = 16L

        fun outputDirectory(): File {
            val path = System.getenv("WIREFRAME_GALLERY_OUT")
            return if (!path.isNullOrEmpty()) File(path) else File("build/wireframe-gallery")
        }
    }
}
