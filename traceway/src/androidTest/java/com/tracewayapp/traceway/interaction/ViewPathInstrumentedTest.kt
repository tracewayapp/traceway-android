package com.tracewayapp.traceway.interaction

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tracewayapp.traceway.PrivacyMasks
import com.tracewayapp.traceway.TracewayMaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hit testing and snapshots on real (unattached, laid out) views. */
@RunWith(AndroidJUnit4::class)
class ViewPathInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A window's content root holding [content], laid out at 1000 x 2000 px. */
    private fun screen(content: View, width: Int = 1000, height: Int = 2000): FrameLayout {
        val root = FrameLayout(context)
        root.id = android.R.id.content
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        return root
    }

    private fun centerOf(view: View, root: ViewGroup): Pair<Float, Float> {
        val rect = Rect(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, rect)
        return rect.exactCenterX() to rect.exactCenterY()
    }

    private fun pathAt(root: ViewGroup, view: View): UiElementPath {
        val (x, y) = centerOf(view, root)
        return ViewPath.pathAt(root, x, y)!!
    }

    private fun sel(selector: String, path: UiElementPath) = ViewSelector.parse(selector)!!.matches(path)

    @Test
    fun theDeepestViewUnderThePointAndItsAncestors() = onMain {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pay = Button(context).apply {
            id = android.R.id.button1
            text = "Pay now"
        }
        column.addView(pay, LinearLayout.LayoutParams(600, 200))
        column.addView(TextView(context).apply { text = "Total" }, LinearLayout.LayoutParams(600, 200))
        val root = screen(column)
        val path = pathAt(root, pay)
        assertEquals("Button", path[0].type)
        assertEquals(listOf("button1"), path[0].ids)
        assertTrue(sel("#button1", path))
        assertTrue(sel("Button[text=\"Pay now\"]", path))
        assertTrue(sel("LinearLayout > Button", path))
        assertTrue(sel(".button", path))
        assertEquals("Button#button1", describeTap(path)!!.selector)
    }

    @Test
    fun theSpaceAroundAButtonIsNotTheButton() = onMain {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pay = Button(context).apply { text = "Pay" }
        column.addView(pay, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val root = screen(column)
        val path = ViewPath.pathAt(root, 10f, pay.bottom + 300f)!!
        assertFalse(sel("Button", path))
        assertTrue(sel("LinearLayout", path))
        assertEquals("LinearLayout", path[0].type)
    }

    @Test
    fun hiddenViewsAreNotHit() = onMain {
        val stack = FrameLayout(context)
        val back = Button(context).apply { text = "Back" }
        val front = Button(context).apply { text = "Front" }
        stack.addView(back, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.addView(front, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val root = screen(stack)
        assertEquals("Front", ViewPath.pathAt(root, 100f, 100f)!![0].text)
        front.visibility = View.GONE
        assertEquals("Back", ViewPath.pathAt(root, 100f, 100f)!![0].text)
        front.visibility = View.INVISIBLE
        assertEquals("Back", ViewPath.pathAt(root, 100f, 100f)!![0].text)
    }

    @Test
    fun elevationComesBeforeChildOrder() = onMain {
        val stack = FrameLayout(context)
        val raised = Button(context).apply {
            text = "Raised"
            elevation = 20f
        }
        val later = Button(context).apply {
            text = "Later"
            stateListAnimator = null
            elevation = 0f
        }
        stack.addView(raised, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.addView(later, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        raised.stateListAnimator = null
        val root = screen(stack)
        assertEquals("Raised", ViewPath.pathAt(root, 100f, 100f)!![0].text)
    }

    @Test
    fun translationAndScaleAreHonored() = onMain {
        val stack = FrameLayout(context)
        val moved = Button(context).apply {
            text = "Moved"
            translationX = 500f
        }
        stack.addView(moved, FrameLayout.LayoutParams(200, 200))
        val root = screen(stack)
        assertEquals("Moved", ViewPath.pathAt(root, 600f, 100f)!![0].text)
        assertEquals("FrameLayout", ViewPath.pathAt(root, 100f, 100f)!![0].type)
        moved.translationX = 0f
        moved.scaleX = 0.25f
        // Scaled around its center (100, 100): only 75..125 is still the button.
        assertEquals("Moved", ViewPath.pathAt(root, 100f, 100f)!![0].text)
        assertEquals("FrameLayout", ViewPath.pathAt(root, 20f, 100f)!![0].type)
    }

    @Test
    fun scrollingIsHonored() = onMain {
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until 20) column.addView(Button(context).apply { text = "Row $i" }, LinearLayout.LayoutParams(MATCH_PARENT, 200))
        scroll.addView(column)
        val root = screen(scroll)
        scroll.scrollTo(0, 1000)
        assertEquals("Row 5", ViewPath.pathAt(root, 100f, 50f)!![0].text)
    }

    @Test
    fun aTouchPassesThroughWhatDoesNotHandleIt() = onMain {
        val stack = FrameLayout(context)
        val button = Button(context).apply { text = "Under" }
        val overlay = TextView(context).apply { text = "Overlay" }
        stack.addView(button, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val root = screen(stack)
        assertEquals("Under", ViewPath.pathAt(root, 100f, 100f)!![0].text)
        overlay.isClickable = true
        assertEquals("Overlay", ViewPath.pathAt(root, 100f, 100f)!![0].text)
    }

    @Test
    fun aTransparentViewIsNotWhatWasTapped() = onMain {
        val stack = FrameLayout(context)
        stack.addView(TextView(context).apply { text = "Shown" }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.addView(TextView(context).apply {
            text = "Faded"
            alpha = 0f
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val root = screen(stack)
        val path = ViewPath.pathAt(root, 100f, 100f)!!
        assertEquals("Shown", path[0].text)
        assertEquals("the faded view's text is not shown text", "Shown", path[1].text)
    }

    @Test
    fun textInsideInputsIsNeverMatched() = onMain {
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val password = EditText(context).apply {
            hint = "Password"
            setText("secret-password")
        }
        form.addView(password, LinearLayout.LayoutParams(MATCH_PARENT, 200))
        val root = screen(form)
        val path = pathAt(root, password)
        assertFalse(sel("EditText[text*=\"secret\"]", path))
        assertFalse(sel("[text*=\"secret\"]", path))
        assertTrue(sel("EditText[text*=\"Password\"]", path))
        assertTrue(sel(".textField", path))
        val target = describeTap(path)!!
        assertFalse(target.selector.contains("secret"))
        assertNotEquals("secret-password", target.text)
    }

    @Test
    fun maskedViewsReportNoTextOrLabel() = onMain {
        val card = FrameLayout(context)
        val number = Button(context).apply { text = "4242 4242 4242 4242" }
        card.addView(number, FrameLayout.LayoutParams(600, 200))
        PrivacyMasks.set(card, TracewayMaskMode.blur())
        val call = ImageButton(context).apply { contentDescription = "Call Ann Smith" }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(card, LinearLayout.LayoutParams(600, 200))
        column.addView(call, LinearLayout.LayoutParams(200, 200))
        call.tag = PrivacyMasks.TAG_BLANK
        val root = screen(column)
        val numberTarget = describeTap(pathAt(root, number))!!
        assertNull(numberTarget.text)
        assertEquals("Button", numberTarget.selector)
        val callTarget = describeTap(pathAt(root, call))!!
        assertNull(callTarget.label)
        assertFalse(callTarget.selector.contains("Ann"))
        assertNull("a mask tag is not a key", pathAt(root, call)[0].key)
    }

    @Test
    fun tagsDescriptionsAndSuperclassTags() = onMain {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat)
        val open = AppCompatButton(themed).apply {
            text = "Open"
            tag = "order.42"
        }
        val delete = ImageButton(context).apply { contentDescription = "Delete" }
        column.addView(open, LinearLayout.LayoutParams(600, 200))
        column.addView(delete, LinearLayout.LayoutParams(200, 200))
        val root = screen(column)
        val openPath = pathAt(root, open)
        assertEquals("AppCompatButton", openPath[0].type)
        assertTrue(sel("Button", openPath))
        assertTrue(sel("[key=\"order.42\"]", openPath))
        assertTrue(sel("[type=\"androidx.appcompat.widget.AppCompatButton\"]", openPath))
        assertEquals("AppCompatButton[key=\"order.42\"]", describeTap(openPath)!!.selector)
        val deletePath = pathAt(root, delete)
        assertEquals("ImageButton[label=\"Delete\"]", describeTap(deletePath)!!.selector)
    }

    @Test
    fun aClickableRowIsNamedByItsText() = onMain {
        val row = LinearLayout(context).apply {
            isClickable = true
            addView(TextView(context).apply { text = "Settings" }, LinearLayout.LayoutParams(400, 200))
            addView(TextView(context).apply { text = "On" }, LinearLayout.LayoutParams(400, 200))
        }
        val root = screen(row)
        val path = ViewPath.pathAt(root, 100f, 100f)!!
        assertEquals("TextView", path[0].type)
        assertEquals("LinearLayout[text=\"Settings On\"]", describeTap(path)!!.selector)
    }

    @Test
    fun aDisabledButtonStillReceivesTheTap() = onMain {
        val stack = FrameLayout(context)
        val behind = Button(context).apply { text = "Behind" }
        val disabled = Button(context).apply {
            text = "Disabled"
            isEnabled = false
        }
        stack.addView(behind, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stack.addView(disabled, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val root = screen(stack)
        assertEquals("Disabled", ViewPath.pathAt(root, 100f, 100f)!![0].text)
    }

    @Test
    fun outsideTheRootThereIsNothing() = onMain {
        val root = screen(FrameLayout(context))
        assertNull(ViewPath.pathAt(root, -1f, 10f))
        assertNull(ViewPath.pathAt(root, 10f, 2000f))
        assertNotNull(ViewPath.pathAt(root, 0f, 0f))
    }

    @Test
    fun leavesIdentifyViewsAndHoverEntersOnlyNewOnes() = onMain {
        val toolbar = LinearLayout(context).apply { id = android.R.id.list }
        val a = Button(context).apply { id = android.R.id.button1 }
        val b = Button(context).apply { id = android.R.id.button2 }
        toolbar.addView(a, LinearLayout.LayoutParams(300, 200))
        toolbar.addView(b, LinearLayout.LayoutParams(300, 200))
        val root = screen(toolbar)
        assertEquals(ViewPath.leafAt(root, 10f, 10f), ViewPath.leafAt(root, 20f, 20f))
        assertNotEquals(ViewPath.leafAt(root, 10f, 10f), ViewPath.leafAt(root, 310f, 10f))

        val hover = HoverTracker()
        val first = hover.move(root, 10f, 10f)!!
        assertTrue(first.entering("#list"))
        assertNull("the same button", hover.move(root, 50f, 50f))
        val second = hover.move(root, 310f, 10f)!!
        assertSame(first.path, second.previous)
        assertTrue(second.entering("#button2"))
        assertFalse(second.entering("#list"))
        hover.reset()
        assertTrue(hover.move(root, 320f, 10f)!!.entering("#list"))
    }
}
