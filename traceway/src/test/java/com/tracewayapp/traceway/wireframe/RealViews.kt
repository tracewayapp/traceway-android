package com.tracewayapp.traceway.wireframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotNull
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** An activity with the Material 3 theme apps use, for the views of a test. */
internal open class TestActivity : AppCompatActivity() {
    protected open val themeId: Int = com.google.android.material.R.style.Theme_Material3_Light_NoActionBar

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeId)
        super.onCreate(savedInstanceState)
    }
}

/** The Material 3 day-night theme: dark under the `night` qualifier. */
internal class DayNightActivity : TestActivity() {
    override val themeId: Int = com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
}

/**
 * Real views in a real activity window, under Robolectric: Android lays
 * them out and draws them, and the capture reads the windows the way it
 * does on a device (through the process's window list).
 */
internal class RealScreen(val controller: ActivityController<out TestActivity>) {
    val activity: TestActivity get() = controller.get()
    val capture = WireframeCapture(activity)
    val density: Float get() = activity.resources.displayMetrics.density
    var now = 1_767_268_800_000L
    private val roots = WireframeRoots()
    private val windows = ArrayList<WireframeRoots.Root>()

    fun px(dp: Float): Int = Math.round(dp * density)

    /** Lets the main thread run a few frames: layout, Compose recompositions, posted tasks. */
    fun idle() {
        // State a test wrote reaches the compositions now. (Compose's own
        // global snapshot loop runs once per process; Robolectric clears
        // the main looper between tests, under it.)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
    }

    /** The windows on screen now, bottom first, drawn as a frame would draw them. */
    fun windows(): List<WireframeRoots.Root> {
        idle()
        roots.current(windows)
        for (w in windows) draw(w.view)
        return ArrayList(windows)
    }

    /**
     * What [view]'s window looks like, drawn in software. Robolectric lays
     * windows out but does not draw them; a drawing sets what a device's
     * frame sets before the capture runs (drawable bounds, text layouts).
     */
    fun draw(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        drawn[view] = bitmap
        return bitmap
    }

    /** The last drawing of each window. */
    val drawn = LinkedHashMap<View, Bitmap>()

    /** The windows as the screen shows them, composed bottom first, for a side-by-side look at a replay. */
    fun screenshot(file: java.io.File) {
        val list = windows()
        val base = list.first().view
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (w in list) {
            if (w.dim > 0f) canvas.drawColor(android.graphics.Color.argb((w.dim * 255).toInt(), 0, 0, 0))
            canvas.drawBitmap(drawn[w.view]!!, w.x.toFloat(), w.y.toFloat(), null)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Captures the windows now, [advanceMs] after the last capture; again
     * when a window that had just opened was left out, as the recorder does.
     */
    fun frame(advanceMs: Long = 100): WireframeFrame {
        now += advanceMs
        var frame = capture.capture(windows(), now, "/test")
        if (capture.deferredWindow) {
            now += 50
            frame = capture.capture(windows(), now, "/test")
        }
        assertNotNull("nothing on screen", frame)
        return frame!!
    }

    fun screen(advanceMs: Long = 100): VNode = frame(advanceMs).screen!!

    companion object {
        /**
         * Frames 16 ms apart, as a display shows them, only as the test moves
         * the clock. By default Robolectric moves the clock itself whenever a
         * frame is asked for, so an endless animation (an indeterminate
         * progress indicator) keeps the looper busy forever.
         */
        fun framesAt60Hz() {
            org.robolectric.shadows.ShadowChoreographer.setPaused(true)
            org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
        }

        fun open(content: (TestActivity) -> View): RealScreen = open(TestActivity::class.java, content)

        fun open(activity: Class<out TestActivity>, content: (TestActivity) -> View): RealScreen {
            framesAt60Hz()
            val controller = Robolectric.buildActivity(activity).setup()
            controller.get().setContentView(content(controller.get()))
            val screen = RealScreen(controller)
            screen.idle()
            return screen
        }
    }
}

/**
 * A Compose view whose compositions recompose on this test's frames. Compose
 * keeps one frame clock per thread for the life of the process, on the
 * thread's `Choreographer` of the time; Robolectric replaces that
 * choreographer between tests, so after the first test state changes would
 * never recompose. This recomposer asks the current one for its frames.
 */
internal fun androidx.compose.ui.platform.ComposeView.recomposingOnTestFrames(): androidx.compose.ui.platform.ComposeView {
    val clock = object : androidx.compose.runtime.MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            kotlinx.coroutines.suspendCancellableCoroutine { c ->
                android.view.Choreographer.getInstance().postFrameCallback { t ->
                    c.resumeWith(runCatching { onFrame(t) })
                }
            }
    }
    val context = kotlinx.coroutines.Dispatchers.Main + clock
    val recomposer = androidx.compose.runtime.Recomposer(context)
    kotlinx.coroutines.CoroutineScope(context).launch { recomposer.runRecomposeAndApplyChanges() }
    setParentCompositionContext(recomposer)
    return this
}

// ---- reading a wireframe -------------------------------------------------------

internal fun texts(n: VNode): List<String> = allNodes(n).filter { it.isText }.map { it.text!! }.toList()

internal fun allText(n: VNode): String = texts(n).joinToString("|")

/** The element whose own text is [text]. */
internal fun withText(root: VNode, text: String): VNode? =
    allNodes(root).firstOrNull { !it.isText && it.children.any { c -> c.isText && c.text == text } }

internal fun style(n: VNode): String = n.attributes["style"] ?: ""

internal fun styles(root: VNode): List<String> = allNodes(root).map(::style).toList()

/** A length in [n]'s style, in px (dp of the device). */
internal fun styleNumber(n: VNode, property: String): Float {
    val m = Regex("(?:^|;)$property:(-?[0-9.]+)px").find(style(n))
    assertNotNull("$property in ${style(n)}", m)
    return m!!.groupValues[1].toFloat()
}

/** Where [node] is on the screen, adding its ancestors' left and top (dp). */
internal fun position(root: VNode, node: VNode): Pair<Float, Float> {
    fun walk(at: VNode, x: Float, y: Float): Pair<Float, Float>? {
        val s = style(at)
        val l = Regex("(?:^|;)left:(-?[0-9.]+)px").find(s)?.groupValues?.get(1)?.toFloat() ?: 0f
        val t = Regex("(?:^|;)top:(-?[0-9.]+)px").find(s)?.groupValues?.get(1)?.toFloat() ?: 0f
        if (at === node) return (x + l) to (y + t)
        for (c in at.children) walk(c, x + l, y + t)?.let { return it }
        return null
    }
    return walk(root, 0f, 0f) ?: error("node not on the screen")
}

/** The ancestors of [node], nearest first. */
internal fun ancestors(root: VNode, node: VNode): List<VNode> {
    fun walk(at: VNode, path: List<VNode>): List<VNode>? {
        if (at === node) return path
        for (c in at.children) walk(c, listOf(at) + path)?.let { return it }
        return null
    }
    return walk(root, emptyList()) ?: error("node not on the screen")
}
