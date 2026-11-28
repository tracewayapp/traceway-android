package com.tracewayapp.traceway.interaction

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tracewayapp.traceway.wireframe.RealScreen
import com.tracewayapp.traceway.wireframe.TestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Taps in the windows an app opens over its activity, a dialog, a bottom
 * sheet, a popup menu, seen the way the SDK sees them in an app: the
 * tracker installed before the activity starts (as `Traceway.init` in
 * `Application.onCreate` does), and every touch delivered to the root view
 * of the window it lands in, as the system delivers it. Nothing here hands
 * the tracker a window: it must find them itself (finding android-005).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class OverlayTapsTest {
    /** The root view of the window each tap was seen in. */
    private val taps = ArrayList<View>()
    private val tracker = TouchTracker().apply {
        addTapListener(object : TapListener {
            override fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {
                taps.add(root)
            }
        })
    }

    @After
    fun tearDown() {
        tracker.uninstall()
        idle()
        FrameworkWindows.popupsReadable = Build.VERSION.SDK_INT < 28
    }

    private fun open(content: (TestActivity) -> View): RealScreen {
        tracker.install(RuntimeEnvironment.getApplication())
        return RealScreen.open(content)
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun touch(root: View, action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(event)
        event.recycle()
    }

    /** A finger down and up on the middle of [target], given to its window's root view; then what it opened shows. */
    private fun tap(target: View) {
        val root = target.rootView
        val at = IntArray(2)
        target.getLocationInWindow(at)
        val x = at[0] + target.width / 2f
        val y = at[1] + target.height / 2f
        val down = SystemClock.uptimeMillis()
        touch(root, MotionEvent.ACTION_DOWN, down, x, y)
        idle(60)
        touch(root, MotionEvent.ACTION_UP, down, x, y)
        repeat(10) { idle() }
    }

    private fun button(a: TestActivity, text: String, onClick: () -> Unit) = Button(a).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun page(a: TestActivity, vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(400, 120))
    }

    @Test
    fun aTapInADialogIsSeen() {
        var dialog: AlertDialog? = null
        var agreed = false
        lateinit var terms: Button
        open { a ->
            terms = button(a, "Terms") {
                dialog = AlertDialog.Builder(a).setTitle("Terms and conditions").setMessage("1. These terms apply.")
                    .setPositiveButton("I agree") { _, _ -> agreed = true }.show()
            }
            page(a, terms)
        }
        tap(terms)
        val shown = dialog!!
        assertEquals("the tap that opened it", 1, taps.size)
        tap(shown.getButton(AlertDialog.BUTTON_POSITIVE))
        assertTrue("the tap reached the dialog's button", agreed)
        assertEquals("the tap in the dialog is seen too", 2, taps.size)
        assertSame(shown.window!!.decorView, taps[1])
    }

    @Test
    fun aTapInABottomSheetIsSeen() {
        var sheet: BottomSheetDialog? = null
        var done = false
        lateinit var edit: Button
        lateinit var doneButton: Button
        open { a ->
            edit = button(a, "Edit items") {
                doneButton = button(a, "Done") { done = true }
                sheet = BottomSheetDialog(a).apply {
                    setContentView(page(a, doneButton))
                    show()
                }
            }
            page(a, edit)
        }
        tap(edit)
        val shown = sheet!!
        assertEquals(1, taps.size)
        tap(doneButton)
        assertEquals("the tap in the sheet is seen too", 2, taps.size)
        assertSame(shown.window!!.decorView, taps[1])
        assertTrue("the tap reached the sheet's button", done)
    }

    /** A ListPopupWindow menu opened by a tap; [onTap] taps its second row. */
    private fun popupMenu(onTap: (row: View, more: Button, picked: () -> Int) -> Unit) {
        var popup: ListPopupWindow? = null
        var picked = -1
        lateinit var more: Button
        open { a ->
            more = button(a, "More") {
                popup = ListPopupWindow(a).apply {
                    anchorView = more
                    width = 400
                    setAdapter(ArrayAdapter(a, android.R.layout.simple_list_item_1, listOf("Ring the bell", "Call on arrival")))
                    setOnItemClickListener { _, _, position, _ ->
                        picked = position
                        dismiss()
                    }
                    show()
                }
            }
            FrameLayout(a).apply { addView(more, FrameLayout.LayoutParams(400, 120)) }
        }
        tap(more)
        onTap(popup!!.listView!!.getChildAt(1), more) { picked }
    }

    /** Below Android 9 the popup's window can be read: its taps are seen too. */
    @Test
    fun aTapInAPopupMenuIsSeen() {
        FrameworkWindows.popupsReadable = true
        popupMenu { row, more, picked ->
            val root = row.rootView
            assertTrue("the popup is a window of its own", root !== more.rootView)
            assertEquals(1, taps.size)
            tap(row)
            assertEquals("the tap reached the popup's row", 1, picked())
            assertEquals("the tap in the popup is seen too", 2, taps.size)
            assertSame(root, taps[1])
        }
    }

    /**
     * From Android 9 a popup's window cannot be read (a blocked framework
     * field): the popup is left exactly as it is, and works.
     */
    @Test
    fun fromAndroid9APopupIsLeftAsItIs() {
        FrameworkWindows.popupsReadable = false
        popupMenu { row, _, picked ->
            tap(row)
            assertEquals("the popup's row still gets its tap", 1, picked())
            assertEquals("only the tap that opened it", 1, taps.size)
        }
    }
}
