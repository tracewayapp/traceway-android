package com.tracewayapp.traceway.wireframe

import android.app.Activity
import android.graphics.Color
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.interaction.TapListener
import com.tracewayapp.traceway.interaction.TouchTracker
import com.tracewayapp.traceway.interaction.ViewPath
import com.tracewayapp.traceway.interaction.describeTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A recorded session of real views and Compose screens, driven the way a
 * user drives them: touches dispatched to the window (taps, a swipe that
 * scrolls), typing, toggles, a dialog and a second screen. The recorder
 * turns it into an rrweb clip, as the SDK uploads it, and the clip must
 * rebuild every frame exactly when it is replayed: at each frame's time,
 * the mirror (a strict model of rrweb's replayer) shows that frame's DOM.
 * The touches are in the clip where the finger was, and every tap is named
 * by the selector the SDK records for it.
 *
 * The clips are written to `build/wireframe-sessions/<name>/` with
 * checkpoints and pictures of the real views at each one, for
 * `tools/wireframe/replay.mjs`, which plays them in the dashboard's rrweb
 * player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WireframeSessionTest {
    /** Records [screen]'s windows on a clock the test moves; frames are kept to check the clip against. */
    private class Session(val screen: RealScreen, name: String) {
        val scheduler = ManualScheduler(screen.now)
        val frames = ArrayList<WireframeFrame>()
        val taps = ArrayList<String>()
        val checkpoints = LinkedHashMap<String, Long>()
        val dir = File("build/wireframe-sessions/$name").apply {
            deleteRecursively()
            mkdirs()
        }
        private val views = ViewFrameSource(WireframeRoots(), screen.activity, mainThread = ManualMainThread(scheduler))
        private val source = object : WireframeFrameSource by views {
            override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
                screen.windows() // drawn, as on a device before the capture runs
                return views.capture(timestampMs, href)?.also { frames.add(it) }
            }
        }
        val recorder = WireframeRecorder(500, 10, source, ManualMainThread(scheduler), scheduler::now)
        val touches = TouchTracker()
        private var down = 0L

        init {
            recorder.screenName = { "/" + screen.activity.javaClass.simpleName }
            touches.addPointerListener(recorder)
            touches.addTapListener(object : TapListener {
                override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {
                    val path = ViewPath.pathAt(root, xPx, yPx) ?: return
                    describeTap(path)?.let { taps.add(it.selector) }
                }
            })
            track(screen.activity)
            recorder.start()
            settle()
        }

        fun track(activity: Activity) = touches.track(activity.window)

        /** Lets the app run, then the recorder capture what changed (it captures after a draw, at most 10 a second). */
        fun settle(ms: Long = 150) {
            screen.idle()
            recorder.onDraw()
            scheduler.advance(ms)
            screen.idle()
        }

        private fun event(action: Int, x: Float, y: Float, at: Long): MotionEvent =
            MotionEvent.obtain(down, at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

        /** The window on top: a dialog's while one is open. */
        private fun window(): android.view.Window {
            val top = screen.windows().last().view
            return windows.firstOrNull { it.peekDecorView() === top } ?: screen.activity.window
        }

        val windows = ArrayList<android.view.Window>()

        /** A finger down and up on the middle of [v]. */
        fun tap(v: View) {
            val at = IntArray(2)
            v.getLocationInWindow(at)
            tapAt(at[0] + v.width / 2f, at[1] + v.height / 2f)
        }

        /** A finger down and up at ([x], [y]) of the window on top, in pixels. */
        fun tapAt(x: Float, y: Float) {
            val w = window()
            down = SystemClock.uptimeMillis()
            w.callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, down))
            scheduler.advance(60)
            SystemClock.sleep(60)
            w.callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis()))
            settle()
        }

        /** A finger dragged from ([x], [y0]) to ([x], [y1]) in [steps] moves. */
        fun swipe(x: Float, y0: Float, y1: Float, steps: Int = 8) {
            val w = window()
            down = SystemClock.uptimeMillis()
            w.callback.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y0, down))
            for (i in 1..steps) {
                scheduler.advance(60)
                SystemClock.sleep(16)
                w.callback.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * i / steps, SystemClock.uptimeMillis()))
                settle(50)
            }
            w.callback.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y1, SystemClock.uptimeMillis()))
            settle()
        }

        /** Types [text] into [field] one character at a time, a frame after each. */
        fun type(field: EditText, text: String) {
            for (ch in text) {
                field.append(ch.toString())
                settle(120)
            }
        }

        fun checkpoint(name: String) {
            settle()
            checkpoints[name] = scheduler.now()
            screen.screenshot(File(dir, "views/$name.png"))
        }

        /** The clip from the first frame to now, checked against every frame, and saved. */
        fun finish(): List<Map<String, Any?>> {
            settle(500)
            val end = scheduler.now()
            val clip = recorder.captureRecording("session", frames.first().timestampMs, end)!!
            @Suppress("UNCHECKED_CAST")
            val events = Json.parse(Json.stringify(clip.events)) as List<Map<String, Any?>>
            File(dir, "events.json").writeText(Json.stringify(events))
            File(dir, "checkpoints.json").writeText(Json.stringify(checkpoints))
            // Every frame, replayed up to its time, is the DOM the capture made.
            for ((i, frame) in frames.withIndex()) {
                val mirror = Mirror()
                mirror.play(events.filter { (it["timestamp"] as Number).toLong() <= frame.timestampMs })
                assertEquals("frame $i at ${frame.timestampMs}", dumpVNode(frame.screen!!), mirror.screen.dump())
            }
            return events
        }
    }

    private fun touches(events: List<Map<String, Any?>>, type: Int): List<Pair<Int, Int>> = events.mapNotNull { e ->
        @Suppress("UNCHECKED_CAST")
        val data = e["data"] as Map<String, Any?>
        if ((e["type"] as Number).toInt() == 3 && (data["source"] as Number).toInt() == 2 && (data["type"] as Number).toInt() == type) {
            (data["x"] as Number).toInt() to (data["y"] as Number).toInt()
        } else {
            null
        }
    }

    @Test
    fun aCheckoutOfViewsReplaysFrameByFrame() {
        lateinit var name: EditText
        lateinit var password: EditText
        lateinit var remember: CheckBox
        lateinit var express: MaterialSwitch
        lateinit var pay: MaterialButton
        lateinit var list: ScrollView
        var dialog: AlertDialog? = null
        val s = RealScreen.open { a ->
            name = EditText(a).apply { id = android.R.id.edit; hint = "Name on card" }
            password = EditText(a).apply {
                id = android.R.id.input
                hint = "Card PIN"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            remember = CheckBox(a).apply { id = android.R.id.checkbox; text = "Remember me" }
            express = MaterialSwitch(a).apply { id = android.R.id.toggle; text = "Express delivery" }
            pay = MaterialButton(a).apply {
                id = android.R.id.button1
                text = "Pay 30.59"
                setOnClickListener {
                    dialog = AlertDialog.Builder(a).setTitle("Payment failed").setMessage("The card was declined.")
                        .setPositiveButton("Retry", null).show()
                }
            }
            list = ScrollView(a).apply {
                id = android.R.id.list
                addView(
                    LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        for (i in 1..30) addView(TextView(a).apply { text = "Order $i"; setPadding(32, 24, 32, 24) })
                    },
                )
            }
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                addView(Toolbar(a).apply { title = "Checkout"; setBackgroundColor(0xFF6750A4.toInt()); setTitleTextColor(Color.WHITE) })
                addView(name)
                addView(password)
                addView(remember)
                addView(express)
                addView(pay)
                addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
        }
        val session = Session(s, "views")
        session.checkpoint("start")

        session.tap(name)
        session.type(name, "Ada Lovelace")
        session.tap(password)
        session.type(password, "1234")
        session.checkpoint("typed")

        session.tap(remember)
        assertTrue("the tap reached the checkbox", remember.isChecked)
        session.tap(express)
        assertTrue(express.isChecked)
        session.checkpoint("toggled")

        val listTop = IntArray(2).also { list.getLocationInWindow(it) }[1]
        session.swipe(200f, listTop + 380f, listTop + 80f)
        assertTrue("the swipe scrolled the list: ${list.scrollY}", list.scrollY > 100)
        session.checkpoint("scrolled")

        session.tap(pay)
        val shown = dialog!!
        session.windows.add(shown.window!!)
        // The tracker finds the dialog's window itself, as in an app: nothing hands it over.
        session.checkpoint("dialog")
        session.tap(shown.getButton(AlertDialog.BUTTON_POSITIVE))
        assertFalse("Retry closed the dialog", shown.isShowing)
        session.checkpoint("closed")

        // A second screen opens over the first.
        val second = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        second.setContentView(TextView(second).apply { text = "Order confirmed"; textSize = 24f })
        s.activity.window.decorView.visibility = View.INVISIBLE // what a stopped activity's window is
        session.checkpoint("next screen")

        val events = session.finish()
        val types = events.map { (it["type"] as Number).toInt() }
        assertEquals("a meta event and a full snapshot first", listOf(4, 2), types.take(2))
        assertTrue("then mutations", types.count { it == 3 } > 30)
        val text = Mirror().apply { play(events) }.texts()
        assertEquals(listOf("Order confirmed"), text)

        // The touches, where the finger was (dp, from the screen's corner).
        val ends = touches(events, 9)
        assertTrue("touch ends: $ends", ends.size >= 6)
        val payAt = IntArray(2).also { pay.getLocationOnScreen(it) }
        assertTrue(ends.contains((payAt[0] + pay.width / 2) to (payAt[1] + pay.height / 2)))
        assertEquals("a click for every tap, none for the swipe", ends.size - 1, touches(events, 2).size)
        assertTrue("the swipe's moves", events.count { ((it["data"] as Map<*, *>)["source"] as Number?)?.toInt() == 6 } >= 4)

        assertEquals(
            listOf("EditText#edit", "EditText#input", "CheckBox#checkbox", "MaterialSwitch#toggle", "MaterialButton#button1", "MaterialButton#button1"),
            session.taps,
        )
    }

    @Test
    fun aComposeScreenReplaysFrameByFrame() {
        val s = RealScreen.open { a ->
            ComposeView(a).recomposingOnTestFrames().apply {
                setContent {
                    MaterialTheme {
                        var count by remember { mutableIntStateOf(0) }
                        var agree by remember { mutableStateOf(false) }
                        var express by remember { mutableStateOf(false) }
                        var name by remember { mutableStateOf("") }
                        Column {
                            Text("Added $count items")
                            Button(onClick = { count++ }, Modifier.testTag("add")) { Text("Add item") }
                            Row { Checkbox(agree, { agree = it }, Modifier.testTag("agree")); Text("I agree") }
                            Switch(express, { express = it }, Modifier.testTag("express"))
                            TextField(name, { name = it }, Modifier.testTag("name"), label = { Text("Name") })
                            LazyColumn(Modifier.testTag("orders")) {
                                items(40) { i -> Text("Order $i", Modifier.height(48.dp).fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
        }
        val session = Session(s, "compose")
        session.checkpoint("start")

        // Taps in the middle of the button, found where the wireframe shows it.
        val button = ancestors(s.screen(0), withText(s.screen(0), "Add item")!!).first()
        val (bx, by) = position(s.screen(0), button)
        val tapX = bx + styleNumber(button, "width") / 2
        val tapY = by + styleNumber(button, "height") / 2
        repeat(3) { session.tapAt(tapX, tapY) }
        assertTrue(allText(s.screen(0)), texts(s.screen(0)).contains("Added 3 items"))
        session.checkpoint("added")

        // The checkbox is the 48 dp box left of its label.
        val label = withText(s.screen(0), "I agree")!!
        val (lx, ly) = position(s.screen(0), label)
        session.tapAt(lx - 24f, ly + styleNumber(label, "height") / 2)
        session.checkpoint("agreed")

        session.swipe(200f, 760f, 460f)
        session.checkpoint("scrolled")
        assertFalse("the list scrolled under the finger", texts(s.screen(0)).contains("Order 0"))

        val events = session.finish()
        assertTrue(Mirror().apply { play(events) }.texts().contains("Added 3 items"))
        assertEquals(listOf("Button#add", "Button#add", "Button#add", "Checkbox#agree"), session.taps)
    }
}
