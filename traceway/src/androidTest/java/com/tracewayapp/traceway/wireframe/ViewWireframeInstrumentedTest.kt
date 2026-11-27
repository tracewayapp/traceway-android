package com.tracewayapp.traceway.wireframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tracewayapp.traceway.PrivacyMasks
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The view walk on a device: real views, laid out by hand (no window), so
 * every position is known. Lengths in the wireframe are dp.
 */
@RunWith(AndroidJUnit4::class)
class ViewWireframeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density

    private fun px(dp: Float) = Math.round(dp * density)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Lays [root] out as a 400 x 800 dp window at the screen's corner. */
    private fun layout(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(px(400f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(px(800f), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, px(400f), px(800f))
    }

    private fun capture(capture: WireframeCapture, root: View, at: Long = System.currentTimeMillis()): VNode {
        val frame = capture.capture(listOf(WireframeRoots.Root(root, WindowManager.LayoutParams.TYPE_APPLICATION, 0f, 0, 0)), at, "/test")
        assertNotNull(frame)
        assertEquals(400f, frame!!.width, 0.6f)
        return frame.screen!!
    }

    private fun all(n: VNode): List<VNode> = listOf(n) + n.children.flatMap { all(it) }

    private fun texts(n: VNode) = all(n).filter { it.isText }.map { it.text!! }

    private fun withText(root: VNode, text: String): VNode? =
        all(root).firstOrNull { !it.isText && it.children.any { c -> c.isText && c.text == text } }

    private fun style(n: VNode) = n.attributes["style"] ?: ""

    /** Where [node] is on the screen, adding its ancestors' left and top (dp). */
    private fun position(root: VNode, node: VNode): Pair<Float, Float> {
        fun walk(at: VNode, x: Float, y: Float): Pair<Float, Float>? {
            val s = style(at)
            val l = Regex("(?:^|;)left:(-?[0-9.]+)px").find(s)?.groupValues?.get(1)?.toFloat() ?: 0f
            val t = Regex("(?:^|;)top:(-?[0-9.]+)px").find(s)?.groupValues?.get(1)?.toFloat() ?: 0f
            if (at === node) return (x + l) to (y + t)
            for (c in at.children) walk(c, x + l, y + t)?.let { return it }
            return null
        }
        return walk(root, 0f, 0f)!!
    }

    @Test
    fun drawsBoxesTextAndTheirPositions() {
        lateinit var screen: VNode
        onMain {
            val root = FrameLayout(context).apply { background = ColorDrawable(Color.WHITE) }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(View(context).apply { background = ColorDrawable(Color.RED) }, LinearLayout.LayoutParams(px(50f), px(20f)))
            column.addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(Color.GREEN)
                        cornerRadius = 8 * density
                        setStroke(px(2f), Color.BLUE)
                    }
                },
                LinearLayout.LayoutParams(px(100f), px(40f)).apply { leftMargin = px(30f) },
            )
            column.addView(TextView(context).apply {
                text = "Hello"
                textSize = 20f
                setTextColor(Color.BLACK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            root.addView(column, FrameLayout.LayoutParams(px(400f), px(800f)).apply { topMargin = px(10f) })
            layout(root)
            screen = capture(WireframeCapture(context), root)
        }
        val styles = all(screen).map(::style)
        assertTrue(styles.any { it.contains("background-color:#ff0000") && it.contains("width:50px") })
        val green = all(screen).first { style(it).contains("background-color:#00ff00") }
        assertTrue(style(green), style(green).contains("border-radius:8px"))
        assertTrue(style(green), style(green).contains("inset 0 0 0"))
        assertTrue(style(green), style(green).contains("#0000ff"))
        assertEquals(30f, position(screen, green).first, 0.6f)
        assertEquals(30f, position(screen, green).second, 0.6f)
        val hello = withText(screen, "Hello")!!
        assertEquals("t n", hello.attributes["class"])
        val fontSize = num1(20f * context.resources.configuration.fontScale)
        assertTrue(style(hello), style(hello).contains("font-size:${fontSize}px"))
        assertTrue(style(hello), style(hello).contains("font-weight:700"))
        assertTrue(style(hello), style(hello).contains("color:#000000"))
    }

    @Test
    fun anUnchangedScreenIsTheSameObjectAndAScrollMovesOneNode() {
        lateinit var scroll: ScrollView
        lateinit var root: FrameLayout
        val capture = WireframeCapture(context)
        lateinit var before: VNode
        lateinit var again: VNode
        lateinit var after: VNode
        onMain {
            root = FrameLayout(context)
            scroll = ScrollView(context)
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (i in 0 until 60) {
                list.addView(TextView(context).apply { text = "Item $i" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50f)))
            }
            scroll.addView(list)
            root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            layout(root)
            before = capture(capture, root)
            again = capture(capture, root)
            scroll.scrollTo(0, px(20f))
            after = capture(capture, root)
        }
        assertSame(before, again)
        assertTrue(texts(before).contains("Item 0"))
        assertFalse("views below the fold are left out", texts(before).contains("Item 40"))
        val m = diffScreens(before, after)!!
        assertEquals(emptyList<Any>(), m["removes"])
        @Suppress("UNCHECKED_CAST")
        val attributes = m["attributes"] as List<Map<String, Any?>>
        assertEquals(1, attributes.size)
        val moved = (attributes.single()["attributes"] as Map<*, *>)["style"] as String
        val top = Regex("^left:0px;top:(-[0-9.]+)px;$").find(moved)!!.groupValues[1].toFloat()
        assertEquals(-20f, top, 0.3f)
    }

    @Test
    fun leavesOutWhatIsNotDrawn() {
        lateinit var screen: VNode
        onMain {
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(TextView(context).apply { text = "shown" })
            root.addView(TextView(context).apply { text = "gone"; visibility = View.GONE })
            root.addView(TextView(context).apply { text = "invisible"; visibility = View.INVISIBLE })
            root.addView(TextView(context).apply { text = "transparent"; alpha = 0f })
            root.addView(TextView(context).apply { text = "faded"; alpha = 0.5f })
            layout(root)
            screen = capture(WireframeCapture(context), root)
        }
        assertEquals(listOf("shown", "faded"), texts(screen))
        assertTrue(all(screen).any { style(it).contains("opacity:0.5") })
    }

    @Test
    fun masksWhatPrivacyMasksAndTextFieldsHold() {
        val capture = WireframeCapture(context)
        lateinit var root: LinearLayout
        lateinit var screen: VNode
        onMain {
            root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(TextView(context).apply {
                text = "secret blank"
                PrivacyMasks.set(this, TracewayMaskMode.blank(0xFF123456.toInt()))
            })
            root.addView(FrameLayout(context).apply {
                tag = PrivacyMasks.TAG_BLUR
                addView(TextView(context).apply { text = "secret blur" })
            })
            root.addView(EditText(context).apply { setText("typed text") })
            root.addView(EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("hunter2")
            })
            root.addView(EditText(context).apply { hint = "Your name" })
            layout(root)
            screen = capture(capture, root)
        }
        val text = texts(screen).joinToString("|")
        assertFalse(text, text.contains("secret"))
        assertTrue(text, text.contains("****** ****"))
        assertTrue(text, text.contains("***** ****"))
        assertFalse(text, text.contains("typed"))
        assertFalse(text, text.contains("hunter2"))
        assertTrue(text, text.contains("•••••••"))
        assertTrue("a hint is not typed text", text.contains("Your name"))
        assertTrue(all(screen).any { style(it).contains("background:#123456") })

        capture.maskAllInputs = false
        onMain { screen = capture(capture, root) }
        assertTrue(texts(screen).contains("typed text"))
        assertFalse(texts(screen).contains("hunter2"))
    }

    @Test
    fun controlsImagesAndPlaceholders() {
        lateinit var screen: VNode
        onMain {
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(CheckBox(context).apply { text = "Remember"; isChecked = true })
            root.addView(
                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 25 },
                LinearLayout.LayoutParams(px(200f), px(16f)),
            )
            root.addView(ImageView(context).apply { setImageDrawable(ColorDrawable(Color.MAGENTA)) }, LinearLayout.LayoutParams(px(30f), px(30f)))
            val bitmap = Bitmap.createBitmap(px(120f), px(80f), Bitmap.Config.ARGB_8888)
            root.addView(ImageView(context).apply { setImageDrawable(BitmapDrawable(context.resources, bitmap)) }, LinearLayout.LayoutParams(px(120f), px(80f)))
            root.addView(SurfaceView(context), LinearLayout.LayoutParams(px(200f), px(90f)))
            root.addView(View(context).apply { rotation = 90f; background = ColorDrawable(Color.CYAN) }, LinearLayout.LayoutParams(px(40f), px(20f)))
            layout(root)
            screen = capture(WireframeCapture(context), root)
        }
        val styles = all(screen).map(::style)
        assertTrue("a checked checkbox", styles.any { it.contains("width:18px;height:18px") && it.contains("background-color") })
        assertTrue(texts(screen).contains("Remember"))
        assertTrue("a determinate bar", styles.any { it.startsWith("width:25%;height:100%;") })
        assertTrue(styles.any { it.contains("background-color:#ff00ff") })
        assertTrue(all(screen).any { it.attributes["class"] == "im" })
        val native = withText(screen, "Native view") ?: withText(screen, "Video")
        assertEquals("pv", native!!.attributes["class"])
        assertEquals(90f, Regex("height:([0-9.]+)px").find(style(native))!!.groupValues[1].toFloat(), 0.6f)
        val rotated = styles.first { it.contains("#00ffff") }
        assertTrue(rotated, rotated.contains("transform:matrix(0,1,-1,0,"))
    }

    @Test
    fun aWindowWithADimSitsOverTheOneBelow() {
        lateinit var screen: VNode
        onMain {
            val page = FrameLayout(context).apply { addView(TextView(context).apply { text = "page" }) }
            layout(page)
            val dialog = FrameLayout(context).apply {
                background = ColorDrawable(Color.WHITE)
                addView(TextView(context).apply { text = "Sure?"; gravity = Gravity.CENTER })
            }
            dialog.measure(
                View.MeasureSpec.makeMeasureSpec(px(300f), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px(200f), View.MeasureSpec.EXACTLY),
            )
            dialog.layout(0, 0, px(300f), px(200f))
            val roots = listOf(
                WireframeRoots.Root(page, WindowManager.LayoutParams.TYPE_APPLICATION, 0f, 0, 0),
                WireframeRoots.Root(dialog, WindowManager.LayoutParams.TYPE_APPLICATION, 0.6f, px(50f), px(300f)),
            )
            val capture = WireframeCapture(context)
            // A window that just opened joins from the next capture, once it is in its place.
            assertEquals(listOf("page"), texts(capture.capture(roots, System.currentTimeMillis(), null)!!.screen!!))
            assertTrue(capture.deferredWindow)
            screen = capture.capture(roots, System.currentTimeMillis(), null)!!.screen!!
        }
        val text = texts(screen)
        assertEquals(listOf("page", "Sure?"), text)
        assertTrue(all(screen).any { style(it).contains("background-color:rgba(0,0,0,0.6)") })
        val sure = withText(screen, "Sure?")!!
        assertEquals(50f, position(screen, sure).first, 0.6f)
    }

    /**
     * Records a short session of real views into a clip and saves it as
     * `files/wireframe-sample/events.json` (pull it with adb and play it
     * with tools/wireframe/replay.mjs), and logs the capture cost.
     */
    @Test
    fun recordsAClipOfRealViews() {
        val capture = WireframeCapture(context)
        lateinit var root: FrameLayout
        lateinit var scroll: ScrollView
        lateinit var check: CheckBox
        onMain {
            root = FrameLayout(context).apply { background = ColorDrawable(0xFFFEF7FF.toInt()) }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(context).apply {
                text = "Wireframe"
                textSize = 22f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xFF6750A4.toInt())
                setPadding(px(16f), px(16f), px(16f), px(16f))
            })
            check = CheckBox(context).apply { text = "Remember me" }
            column.addView(check)
            scroll = ScrollView(context)
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (i in 0 until 40) {
                list.addView(TextView(context).apply { text = "Row $i"; setPadding(px(16f), px(12f), px(16f), px(12f)) })
            }
            scroll.addView(list)
            column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(column)
            layout(root)
        }
        val source = object : WireframeFrameSource {
            override var maskAllInputs = true
            override fun observe(onDraw: () -> Unit) {}
            override fun stopObserving() {}
            override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
                var frame: WireframeFrame? = null
                onMain {
                    frame = capture.capture(listOf(WireframeRoots.Root(root, WindowManager.LayoutParams.TYPE_APPLICATION, 0f, 0, 0)), timestampMs, href)
                }
                return frame
            }
            override fun reset() = capture.reset()
        }
        val scheduler = ManualScheduler(System.currentTimeMillis())
        val main = object : MainThread {
            override fun isCurrent() = true
            override fun post(delayMs: Long, task: () -> Unit): Cancellable = scheduler.schedule(delayMs, task)
        }
        val recorder = WireframeRecorder(150, 10, source, main, scheduler::now)
        val start = scheduler.now()
        recorder.start()
        scheduler.runDue()
        for (i in 1..30) {
            scheduler.advance(100)
            onMain {
                scroll.scrollTo(0, px(i * 12f))
                if (i == 15) check.isChecked = true
                layout(root)
            }
            recorder.onDraw()
            scheduler.runDue()
        }
        Log.i("Traceway", "wireframe capture of real views: ${"%.3f".format(recorder.averageCaptureMs)} ms per capture")
        scheduler.advance(500)
        val clip = recorder.captureRecording("device", start, scheduler.now())!!
        assertTrue(clip.events.size > 30)
        val dir = File(context.filesDir, "wireframe-sample").apply { mkdirs() }
        File(dir, "events.json").writeText(Json.stringify(clip.events))
        Log.i("Traceway", "wireframe sample clip: ${File(dir, "events.json").absolutePath} (${clip.approxBytes} bytes)")
    }
}
