package com.tracewayapp.traceway.wireframe

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.LinearLayout
import android.widget.TextView
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.compose.tracewayMask
import com.tracewayapp.traceway.interaction.ViewPath
import com.tracewayapp.traceway.interaction.describeTap
import androidx.compose.foundation.clickable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wireframe of Jetpack Compose screens, read from their semantics tree,
 * composable by composable: text, buttons, fields, toggles, sliders,
 * progress, icons, lists, tabs, dialogs and menus, each where Compose puts
 * it. The screen is 400 x 800 dp. The declarative topics of the wireframe
 * suites every SDK shares come first, in their order (41 to 48; the iOS
 * SDK's SwiftUI tests mirror them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeWireframeTest {
    private fun compose(content: @Composable () -> Unit): RealScreen = RealScreen.open { a ->
        ComposeView(a).recomposingOnTestFrames().apply { setContent { MaterialTheme { content() } } }
    }

    @Test
    fun textHasItsStyleAndPlace() {
        val s = compose {
            Column(Modifier.padding(start = 20.dp, top = 30.dp)) {
                Text("Hello", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                Box(Modifier.width(120.dp)) { Text("Centered", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                Box(Modifier.width(120.dp)) { Text("A line far too long for its box", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Box(Modifier.width(120.dp)) { Text("Two lines that wrap around in the narrow box") }
                Box(Modifier.width(40.dp)) { Text("Cut without dots", maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }
        val screen = s.screen()
        val hello = withText(screen, "Hello")!!
        assertEquals("one line that spills over rather than being cut", "t n", hello.attributes["class"])
        assertTrue(style(hello), style(hello).contains("font-size:20px;"))
        assertTrue(style(hello), style(hello).contains("color:#ff0000;font-weight:700;"))
        assertEquals(20f to 30f, position(screen, hello))
        val centered = withText(screen, "Centered")!!
        assertTrue(style(centered), style(centered).contains("justify-content:center;"))
        assertEquals(120f, styleNumber(centered, "width"))
        assertEquals("t n e", withText(screen, "A line far too long for its box")!!.attributes["class"])
        assertEquals("t", withText(screen, "Two lines that wrap around in the narrow box")!!.attributes["class"])
        assertEquals("cut by the app, so cut in the replay", "t n k", withText(screen, "Cut without dots")!!.attributes["class"])
    }

    @Test
    fun buttonsAndIconsShowTheirLook() {
        val s = compose {
            Column {
                Button(onClick = {}) { Text("Pay") }
                OutlinedButton(onClick = {}) { Text("Cancel") }
                TextButton(onClick = {}, enabled = false) { Text("Later") }
                IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            }
        }
        val screen = s.screen()
        val pay = ancestors(screen, withText(screen, "Pay")!!).first()
        // Its white text tells a filled button: the theme's primary color behind it.
        assertTrue("a filled, rounded button: ${style(pay)}", style(pay).contains("background-color:#6750a4;") && style(pay).contains("border-radius:20px;"))
        val cancel = ancestors(screen, withText(screen, "Cancel")!!).first()
        // Its text in the primary color tells an outlined button: an outline and no fill.
        assertTrue("an outlined one: ${style(cancel)}", style(cancel).contains("box-shadow:inset 0 0 0 1px #79747e;"))
        assertFalse(style(cancel), style(cancel).contains("background-color"))
        assertEquals("the text inside the button", "Pay", pay.children.single { !it.isText }.children.single().text)
        val later = ancestors(screen, withText(screen, "Later")!!).first()
        assertTrue("disabled: ${style(later)}", style(later).contains("opacity:0.38;"))
        val icon = allNodes(screen).single { it.attributes["class"] == "ic" }
        assertEquals(24f, styleNumber(icon, "width"))
    }

    @Test
    fun fieldsMaskTheirTextAndBulletSecrets() {
        val s = compose {
            Column {
                TextField(value = "typed text", onValueChange = {}, label = { Text("Name") })
                OutlinedTextField(
                    value = "hunter2",
                    onValueChange = {},
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextField(value = "", onValueChange = {}, placeholder = { Text("Search") })
            }
        }
        var screen = s.screen()
        val text = allText(screen)
        assertFalse(text, text.contains("typed"))
        assertFalse(text, text.contains("hunter2"))
        assertTrue("labels and placeholders are not typed text: $text", texts(screen).containsAll(listOf("Name", "Password", "Search")))
        assertTrue(text, text.contains("***** ****"))
        assertTrue(text, text.contains("•••••••"))
        // A TextField is filled; an OutlinedTextField with a label is outlined below the room for its label.
        val name = ancestors(screen, withText(screen, "Name")!!).first { style(it).contains("height:") && styleNumber(it, "height") >= 48f }
        assertTrue(style(name), style(name).contains("background-color:#e6e0e9;border-radius:4px 4px 0 0;box-shadow:inset 0 -"))
        val password = ancestors(screen, withText(screen, "Password")!!).first { style(it).contains("height:") && styleNumber(it, "height") >= 48f }
        val outline = password.children.first { !it.isText && style(it).contains("box-shadow:inset 0 0 0 1px") }
        assertEquals(8f, styleNumber(outline, "top"))
        s.capture.maskAllInputs = false
        screen = s.screen()
        assertTrue(allText(screen), texts(screen).contains("typed text"))
        assertFalse(allText(screen).contains("hunter2"))
    }

    @Test
    fun togglesSlidersAndProgressAreDrawnFromTheirState() {
        val s = compose {
            Column {
                Checkbox(checked = true, onCheckedChange = {})
                Checkbox(checked = false, onCheckedChange = {})
                Switch(checked = true, onCheckedChange = {})
                Switch(checked = false, onCheckedChange = {})
                RadioButton(selected = true, onClick = {})
                Slider(value = 0.5f, onValueChange = {}, modifier = Modifier.width(300.dp))
                LinearProgressIndicator(progress = { 0.25f }, modifier = Modifier.width(200.dp))
                CircularProgressIndicator()
            }
        }
        val screen = s.screen()
        val all = styles(screen)
        assertTrue(all.joinToString("\n"), all.any { it.contains("width:18px;height:18px;border-radius:2px;background-color:#6750a4;") })
        assertTrue("an unchecked box", all.any { it.contains("border-radius:2px;box-shadow:inset 0 0 0 2px #79747e;") })
        assertTrue("an on switch", all.any { it.contains("width:52px;height:32px;border-radius:16px;background-color:#6750a4;") })
        assertTrue("an off switch", all.any { it.contains("width:52px;height:32px;border-radius:16px;box-shadow:inset 0 0 0 2px #79747e;") })
        assertTrue("a selected radio", all.any { it.startsWith("left:5px;top:5px;width:10px;height:10px;border-radius:50%;") })
        assertTrue("the bar at a quarter", all.any { it.startsWith("width:25%;height:100%;") })
        assertTrue("the slider's thumb", all.any { it.contains("width:20px;height:20px;border-radius:50%;") })
        assertTrue("an indeterminate ring", all.any { it.contains("border-radius:50%;border:") })
    }

    @Test
    fun aLazyListScrollsAndReplays() {
        val state = LazyListState()
        val s = compose {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(200) { i -> Text("Item $i", Modifier.height(50.dp).fillMaxWidth()) }
            }
        }
        var before = s.screen()
        assertTrue(texts(before).contains("Item 0"))
        assertFalse(texts(before).contains("Item 40"))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        for (step in 1..5) {
            runBlocking { state.scrollBy(130f) }
            val after = s.screen()
            mirror.mutation(diffScreens(before, after)!!)
            assertEquals("step $step", dumpVNode(after), mirror.screen.dump())
            before = after
        }
        val item = withText(before, "Item 20")!!
        assertEquals("rows sit where the list draws them", 20 * 50f - 650f, position(before, item).second, 0.6f)
        // The text is at the top of its taller row, as Compose draws it, not in the middle.
        assertTrue(style(item), styleNumber(item, "line-height") < 25f)
        assertTrue("the list clips its rows", allNodes(before).any { style(it).contains("overflow:hidden;") })
    }

    @Test
    fun masksHideWhatTheyCover() {
        val s = compose {
            Column {
                Text("secret blur", Modifier.tracewayMask())
                Box(Modifier.tracewayMask(TracewayMaskMode.blank(0xFF123456.toInt()))) { Text("secret blank") }
                Text("tagged", Modifier.testTag("traceway-mask"))
                Box(Modifier.testTag("traceway-blank").size(40.dp)) { Text("blank tag") }
                Text("visible")
            }
        }
        val screen = s.screen()
        val text = texts(screen)
        assertEquals(listOf("****** ****", "******", "visible"), text)
        assertTrue(styles(screen).any { it.contains("background:#123456;") })
        assertEquals(2, allNodes(screen).count { it.attributes["class"] == "mk" })
    }

    @Test
    fun aStateChangeReplaysInTheMirror() {
        val count: MutableState<Int> = mutableStateOf(0)
        val checked = mutableStateOf(false)
        val s = compose {
            Column {
                Text("Count ${count.value}")
                Row { Checkbox(checked = checked.value, onCheckedChange = {}); Text("Agree") }
                if (count.value % 2 == 1) Text("Odd")
            }
        }
        var before = s.screen()
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        val steps = listOf({ count.value = 1 }, { checked.value = true }, { count.value = 2 }, { checked.value = false; count.value = 3 })
        for ((i, step) in steps.withIndex()) {
            step()
            val after = s.screen()
            diffScreens(before, after)?.let { mirror.mutation(it) }
            assertEquals("step $i", dumpVNode(after), mirror.screen.dump())
            before = after
        }
        assertEquals(listOf("Count 3", "Agree", "Odd"), mirror.texts())
    }

    @Test
    fun declarativeInsideAClassicHierarchyIsPlacedWithIt() {
        val s = RealScreen.open { a ->
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(a).apply { text = "A view"; height = 100 })
                addView(ComposeView(a).recomposingOnTestFrames().apply { setContent { MaterialTheme { Text("Composed", Modifier.padding(start = 10.dp)) } } })
            }
        }
        val screen = s.screen()
        assertEquals(listOf("A view", "Composed"), texts(screen))
        assertEquals(10f to 100f, position(screen, withText(screen, "Composed")!!))
    }

    @Test
    fun aMaskTestTagMasksWhatTapsReportToo() {
        val s = compose {
            Column {
                Text("Card 4242", Modifier.testTag("traceway-mask").clickable {}.padding(8.dp))
                Text("Plain row", Modifier.testTag("plain").clickable {}.padding(8.dp))
            }
        }
        val screen = s.screen()
        fun tapOn(shown: String) = run {
            val node = withText(screen, shown)!!
            val (x, y) = position(screen, node)
            val root = s.activity.window.decorView
            describeTap(ViewPath.pathAt(root, s.px(x + 4f).toFloat(), s.px(y + 4f).toFloat())!!)!!
        }
        val masked = tapOn("**** ****")
        assertEquals(null, masked.text)
        assertFalse("the mask tag names nothing", masked.selector.contains("traceway-mask"))
        val plain = tapOn("Plain row")
        assertEquals("Plain row", plain.text)
        assertEquals("plain", plain.id)
    }

    @Test
    fun tabsShowWhichIsSelected() {
        val s = compose {
            TabRow(selectedTabIndex = 1) {
                Tab(selected = false, onClick = {}, text = { Text("Cart") })
                Tab(selected = true, onClick = {}, text = { Text("Shipping") })
            }
        }
        val screen = s.screen()
        val shipping = ancestors(screen, withText(screen, "Shipping")!!).first { style(it).contains("box-shadow:inset 0 -3px 0 0") }
        assertTrue(style(shipping), styleNumber(shipping, "width") > 100f)
        assertFalse(ancestors(screen, withText(screen, "Cart")!!).any { style(it).contains("box-shadow:inset 0 -3px") })
    }

    @Test
    fun aDialogAndAMenuAreWindowsOverTheScreen() {
        val open = mutableStateOf(false)
        val menu = mutableStateOf(false)
        val s = compose {
            Box(Modifier.fillMaxSize()) {
                Text("page")
                if (open.value) AlertDialog(onDismissRequest = {}, confirmButton = { TextButton(onClick = {}) { Text("Delete") } }, title = { Text("Sure?") })
                Box(Modifier.padding(top = 100.dp)) {
                    DropdownMenu(expanded = menu.value, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text("Share") }, onClick = {})
                    }
                }
            }
        }
        assertEquals(listOf("page"), texts(s.screen()))
        open.value = true
        val dialog = s.screen()
        assertEquals(2, s.windows().size)
        val text = texts(dialog)
        assertTrue(text.toString(), text.containsAll(listOf("page", "Sure?", "Delete")))
        assertTrue(text.indexOf("page") < text.indexOf("Sure?"))
        assertTrue("the dialog dims the page", styles(dialog).any { it.startsWith("left:0;top:0;width:400px;height:800px;background-color:rgba(0,0,0,") })
        open.value = false
        menu.value = true
        val menuScreen = s.screen()
        assertEquals(listOf("page", "Share"), texts(menuScreen))
        // The menu is where its popup window is, over the page.
        val popup = s.windows().last()
        val (shareX, shareY) = position(menuScreen, withText(menuScreen, "Share")!!)
        val inPopup = shareX >= popup.x / s.density && shareX < (popup.x + popup.view.width) / s.density &&
            shareY >= popup.y / s.density && shareY < (popup.y + popup.view.height) / s.density
        assertTrue("Share at $shareX,$shareY; popup at ${popup.x},${popup.y} ${popup.view.width}x${popup.view.height}", inPopup)
    }
}
