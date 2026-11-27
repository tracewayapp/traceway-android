package com.tracewayapp.traceway.wireframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayMaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wireframe of real Android views, widget by widget, as the Flutter
 * SDK's `wireframe_test.dart` checks its widgets: Android lays the views out
 * (Robolectric runs the framework on the JVM, with its native graphics) and
 * every box, text, control and placeholder is checked where the replay
 * shows it. Lengths in the wireframe are dp; the screen is 400 x 800 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewWireframeTest {
    private fun column(a: Context, vararg views: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for (v in views) addView(v)
    }

    private fun sized(v: View, w: Int, h: Int): View = v.apply { layoutParams = LinearLayout.LayoutParams(w, h) }

    /** Where [v] is on the screen, in dp. */
    private fun onScreen(s: RealScreen, v: View): Pair<Float, Float> {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return at[0] / s.density to at[1] / s.density
    }

    private fun assertAt(s: RealScreen, screen: VNode, node: VNode, v: View) {
        val (x, y) = onScreen(s, v)
        val (nx, ny) = position(screen, node)
        assertEquals("left of ${dumpVNode(node)}", x, nx, 0.6f)
        assertEquals("top of ${dumpVNode(node)}", y, ny, 0.6f)
    }

    @Test
    fun drawsBoxesTextDecorationsAndTheirPositions() {
        lateinit var green: View
        lateinit var hello: TextView
        val s = RealScreen.open { a ->
            green = View(a).apply {
                background = GradientDrawable().apply {
                    setColor(Color.GREEN)
                    cornerRadius = 8f
                    setStroke(2, Color.BLUE)
                }
                elevation = 4f
                layoutParams = LinearLayout.LayoutParams(100, 40).apply { leftMargin = 30 }
            }
            hello = TextView(a).apply {
                text = "Hello"
                textSize = 20f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
            }
            FrameLayout(a).apply {
                background = ColorDrawable(Color.WHITE)
                addView(
                    column(
                        a,
                        sized(View(a).apply { setBackgroundColor(Color.RED) }, 50, 20),
                        green,
                        sized(
                            View(a).apply {
                                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.WHITE, Color.BLACK))
                            },
                            60, 30,
                        ),
                        hello,
                    ),
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = 10 },
                )
            }
        }
        val screen = s.screen()
        val all = styles(screen)
        assertTrue(dumpVNode(screen), all.any { it.contains("background-color:#ff0000") && it.contains("width:50px;height:20px") })
        val box = allNodes(screen).first { style(it).contains("background-color:#00ff00") }
        assertTrue(style(box), style(box).contains("border-radius:8px;"))
        assertTrue("outline inside, elevation below: ${style(box)}", style(box).contains("box-shadow:0 2px 6.4px 0 rgba(0,0,0,0.278),inset 0 0 0 2px #0000ff;"))
        assertAt(s, screen, box, green)
        assertTrue(all.joinToString("\n"), all.any { it.contains("background-image:linear-gradient(90deg,#ffffff,#000000);") })

        val text = withText(screen, "Hello")!!
        assertEquals("t n", text.attributes["class"])
        assertTrue(style(text), style(text).contains("font-size:20px;"))
        assertTrue(style(text), style(text).contains("color:#000000;font-weight:700;"))
        assertAt(s, screen, text, hello)
        assertEquals(400f, styleNumber(screen, "width"))
        assertEquals(800f, styleNumber(screen, "height"))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun everyLengthIsInDp() {
        lateinit var box: View
        val s = RealScreen.open { a ->
            box = View(a).apply {
                setBackgroundColor(Color.RED)
                layoutParams = FrameLayout.LayoutParams(150, 60).apply {
                    leftMargin = 30
                    topMargin = 90
                }
            }
            FrameLayout(a).apply { addView(box) }
        }
        assertEquals(3f, s.density)
        val screen = s.screen()
        val red = allNodes(screen).first { style(it).contains("#ff0000") }
        assertEquals(50f, styleNumber(red, "width"))
        assertEquals(20f, styleNumber(red, "height"))
        assertEquals(10f to 30f, position(screen, red))
        assertEquals(400f, styleNumber(screen, "width"))
    }

    @Test
    fun anUnchangedScreenIsTheSameObjectAndAScrollMovesOneNode() {
        lateinit var scroll: ScrollView
        val s = RealScreen.open { a ->
            scroll = ScrollView(a).apply {
                addView(column(a, *Array(60) { i -> sized(TextView(a).apply { text = "Item $i" }, MATCH_PARENT, 50) }))
            }
            scroll
        }
        val before = s.screen()
        assertSame("a screen that did not change is the same object", before, s.screen())
        assertTrue(allText(before).contains("Item 0"))
        assertFalse("rows below the fold are left out", allText(before).contains("Item 40"))
        scroll.scrollTo(0, 20)
        val after = s.screen()
        val m = diffScreens(before, after)!!
        assertEquals(emptyList<Any>(), m["removes"])
        @Suppress("UNCHECKED_CAST")
        val attributes = m["attributes"] as List<Map<String, Any?>>
        assertEquals("only the content node moves: $attributes", 1, attributes.size)
        assertEquals(mapOf("style" to "left:0px;top:-20px;"), attributes.single()["attributes"])
        @Suppress("UNCHECKED_CAST")
        val added = (m["adds"] as List<Map<String, Any?>>).mapNotNull { (it["node"] as Map<*, *>)["textContent"] }
        assertEquals("the row that scrolled into view", listOf("Item 16"), added)

        scroll.scrollTo(0, 2000)
        val far = s.screen()
        assertTrue(allText(far).contains("Item 40"))
        assertFalse(texts(far).contains("Item 0"))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(after)))
        mirror.mutation(diffScreens(after, far)!!)
        assertEquals(dumpVNode(far), mirror.screen.dump())
    }

    @Test
    fun aRecyclerViewKeepsItsRowsAsItScrolls() {
        lateinit var list: RecyclerView
        val s = RealScreen.open { a ->
            list = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(TextView(a).apply { layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, 50) }) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).text = "Row $position"
                    }

                    override fun getItemCount() = 200
                }
            }
            list
        }
        var before = s.screen()
        assertTrue(allText(before), texts(before).containsAll(listOf("Row 0", "Row 15")))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        for (step in 1..6) {
            list.scrollBy(0, 170)
            val after = s.screen()
            mirror.mutation(diffScreens(before, after)!!)
            assertEquals("step $step", dumpVNode(after), mirror.screen.dump())
            before = after
        }
        // Every row shown is where the list draws it.
        val holder = list.findViewHolderForAdapterPosition(25)!!
        assertAt(s, before, withText(before, "Row 25")!!, holder.itemView)
    }

    @Test
    fun leavesOutWhatIsNotDrawn() {
        val s = RealScreen.open { a ->
            column(
                a,
                TextView(a).apply { text = "shown" },
                TextView(a).apply { text = "gone"; visibility = View.GONE },
                TextView(a).apply { text = "invisible"; visibility = View.INVISIBLE },
                TextView(a).apply { text = "transparent"; alpha = 0f },
                TextView(a).apply { text = "faded"; alpha = 0.5f },
                sized(TextView(a).apply { text = "no room" }, 0, 0),
                FrameLayout(a).apply {
                    layoutParams = LinearLayout.LayoutParams(100, 20)
                    // Laid out to the right of its clipping parent.
                    addView(TextView(a).apply { text = "clipped away" }, FrameLayout.LayoutParams(100, 20).apply { leftMargin = 150 })
                },
                FrameLayout(a).apply {
                    addView(TextView(a).apply { text = "under a blank mask" })
                    Traceway.mask(this, TracewayMaskMode.blank())
                },
            )
        }
        val screen = s.screen()
        assertEquals(listOf("shown", "faded"), texts(screen))
        assertTrue(styles(screen).any { it.contains("opacity:0.5;") })
    }

    @Test
    fun aDialogShowsOverThePageBehindItsDim() {
        val s = RealScreen.open { a -> FrameLayout(a).apply { addView(TextView(a).apply { text = "page" }) } }
        val dialog = AlertDialog.Builder(s.activity).setTitle("Sure?").setMessage("This cannot be undone").setPositiveButton("Delete", null).create()
        dialog.show()
        val windows = s.windows()
        assertEquals("the activity and the dialog: ${windows.map { it.view }}", 2, windows.size)
        assertTrue("a dialog dims what is behind it", windows[1].dim > 0f)
        val screen = s.screen()
        val text = texts(screen)
        assertTrue(text.toString(), text.containsAll(listOf("page", "Sure?", "This cannot be undone")))
        assertTrue("the dialog is above the page: $text", text.indexOf("page") < text.indexOf("Sure?"))
        val dim = allNodes(screen).first { style(it).startsWith("left:0;top:0;width:400px;height:800px;background-color:rgba(0,0,0,") }
        assertTrue("the dim is between the page and the dialog", screen.children.indexOf(dim) in 1 until screen.children.size - 1)
        val title = withText(screen, "Sure?")!!
        assertAt(s, screen, title, dialog.findViewById<View>(androidx.appcompat.R.id.alertTitle)!!)
        // A text button has no box of its own: its text is where the button draws it.
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val (bx, by) = onScreen(s, button)
        val (tx, ty) = position(screen, withText(screen, "Delete")!!)
        assertEquals(bx + button.totalPaddingLeft / s.density, tx, 0.6f)
        assertEquals(by + button.totalPaddingTop / s.density, ty, 0.6f)

        dialog.dismiss()
        val closed = s.screen()
        assertEquals(listOf("page"), texts(closed))
    }

    @Test
    fun aPopupSitsAboveItsWindow() {
        lateinit var anchor: TextView
        val s = RealScreen.open { a ->
            anchor = TextView(a).apply { text = "anchor" }
            column(a, anchor, TextView(a).apply { text = "below" })
        }
        val popup = PopupWindow(
            TextView(s.activity).apply {
                text = "menu item"
                setBackgroundColor(Color.WHITE)
            },
            200, 48,
        )
        popup.showAsDropDown(anchor)
        val windows = s.windows()
        assertEquals(2, windows.size)
        val screen = s.screen()
        assertEquals(listOf("anchor", "below", "menu item"), texts(screen))
        assertAt(s, screen, withText(screen, "menu item")!!, popup.contentView)
        popup.dismiss()
    }

    @Test
    fun masksWhatPrivacyMasksAndTextFieldsHold() {
        lateinit var layout: TextInputLayout
        lateinit var field: TextInputEditText
        val s = RealScreen.open { a ->
            field = TextInputEditText(a).apply { setText("ada@example.com") }
            layout = TextInputLayout(a).apply {
                hint = "Email"
                addView(field)
            }
            column(
                a,
                TextView(a).apply {
                    text = "secret blank"
                    Traceway.mask(this, TracewayMaskMode.blank(0xFF123456.toInt()))
                },
                FrameLayout(a).apply {
                    tag = "traceway-mask"
                    addView(TextView(a).apply { text = "secret blur" })
                },
                TextView(a).apply {
                    text = "tagged blank"
                    tag = "traceway-blank"
                },
                EditText(a).apply { setText("typed text") },
                EditText(a).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText("hunter2")
                },
                EditText(a).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    setText("1234")
                },
                EditText(a).apply { hint = "Your name" },
                layout,
            )
        }
        var screen = s.screen()
        val text = allText(screen)
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("tagged"))
        assertTrue("a blurred mask keeps the shape of words: $text", text.contains("****** ****"))
        assertTrue(text, text.contains("***** ****"))
        assertFalse(text, text.contains("typed"))
        assertFalse(text, text.contains("hunter2"))
        assertTrue("password fields show dots: $text", text.contains("•••••••"))
        assertTrue(text, text.contains("••••"))
        assertTrue("a hint is not typed text: $text", text.contains("Your name"))
        assertFalse(text, text.contains("ada@"))
        assertTrue("a filled Material field floats its label: $text", texts(screen).contains("Email"))
        val blank = allNodes(screen).first { style(it).contains("background:#123456;") }
        assertEquals("mk", blank.attributes["class"])
        assertEquals("both blank masks", 2, allNodes(screen).count { it.attributes["class"] == "mk" })

        s.capture.maskAllInputs = false
        screen = s.screen()
        assertTrue(allText(screen), texts(screen).containsAll(listOf("typed text", "ada@example.com")))
        assertFalse("a password stays masked", allText(screen).contains("hunter2"))
        assertFalse(allText(screen).contains("1234"))
    }

    @Test
    fun togglesProgressAndSlidersAreDrawnFromTheirState() {
        lateinit var check: CheckBox
        lateinit var material: MaterialSwitch
        val s = RealScreen.open { a ->
            check = CheckBox(a).apply { text = "Remember me"; isChecked = true }
            material = MaterialSwitch(a).apply { text = "Wi-Fi"; isChecked = true }
            column(
                a,
                check,
                CheckBox(a).apply { text = "Off"; isEnabled = false },
                RadioButton(a).apply { text = "Card"; isChecked = true },
                Switch(a).apply { text = "Framework switch"; isChecked = false },
                SwitchCompat(a).apply { text = "Compat switch"; isChecked = true },
                material,
                ToggleButton(a).apply { isChecked = true },
                sized(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 25 }, 200, 16),
                sized(ProgressBar(a).apply { isIndeterminate = true }, 48, 48),
                sized(SeekBar(a).apply { max = 10; progress = 5 }, 300, 40),
                RatingBar(a).apply { numStars = 5; rating = 3f },
            )
        }
        val screen = s.screen()
        val all = styles(screen)
        val text = texts(screen)
        assertTrue(text.toString(), text.containsAll(listOf("Remember me", "Off", "Card", "Framework switch", "Compat switch", "Wi-Fi", "ON")))
        // A checked box is filled with the theme's primary color and has a tick.
        assertTrue(all.joinToString("\n"), all.any { it.contains("width:18px;height:18px;border-radius:2px;background-color:") })
        assertTrue(all.any { it.startsWith("left:6px;top:2px;width:6px;height:11px;border:solid #fff") })
        assertTrue("an unchecked, disabled box is an outline", all.any { it.contains("border-radius:2px;box-shadow:inset 0 0 0 2px rgba(") })
        assertTrue("a checked radio has its dot", all.any { it.startsWith("left:5px;top:5px;width:10px;height:10px;border-radius:50%;") })
        // Switches: a Material 3 track is filled when on, outlined when off.
        assertTrue(all.any { it.contains("width:52px;height:32px;border-radius:16px;background-color:") })
        assertTrue("an off framework switch", all.any { it.contains("border-radius:7px;background-color:rgba(") })
        val bar = allNodes(screen).first { style(it).startsWith("width:25%;height:100%;") }
        assertNotNull(bar)
        assertTrue("an indeterminate ring", all.any { it.contains("border-radius:50%;border:") && it.contains("border-top-color:") })
        assertTrue("the slider's thumb is half way", all.any { it.contains("width:20px;height:20px;border-radius:50%;") })
        assertTrue(text.toString(), text.contains("★★★☆☆"))
        // The toggles sit where the views draw them.
        assertAt(s, screen, ancestors(screen, withText(screen, "Remember me")!!).first(), check)
        assertAt(s, screen, ancestors(screen, withText(screen, "Wi-Fi")!!).first(), material)
    }

    @Test
    fun imagesIconsAndTheirGlyphs() {
        lateinit var photo: ImageView
        val s = RealScreen.open { a ->
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
            photo = ImageView(a).apply {
                setImageDrawable(BitmapDrawable(a.resources, bitmap))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(240, 80)
            }
            fun icon(color: Int) = ImageView(a).apply {
                setImageDrawable(ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_search_api_material)!!.mutate().apply { setTint(color) })
                layoutParams = LinearLayout.LayoutParams(24, 24)
            }
            column(
                a,
                photo,
                icon(Color.RED),
                icon(Color.BLUE),
                sized(ImageView(a).apply { setImageDrawable(ColorDrawable(Color.MAGENTA)) }, 30, 30),
            )
        }
        val screen = s.screen()
        val image = allNodes(screen).single { it.attributes["class"] == "im" }
        // FIT_CENTER: the picture in the middle of its view, as tall as it.
        assertEquals(120f, styleNumber(image, "width"))
        assertEquals(80f, styleNumber(image, "height"))
        assertEquals(60f, position(screen, image).first, 0.6f)
        val icons = allNodes(screen).filter { (it.attributes["class"] ?: "").startsWith("ic") }.toList()
        assertEquals(2, icons.size)
        assertTrue(style(icons[0]), style(icons[0]).contains("color:#ff0000;"))
        assertTrue(style(icons[1]), style(icons[1]).contains("color:#0000ff;"))
        assertTrue(styles(screen).any { it.contains("background-color:#ff00ff") })

        // The icon's picture is drawn off the main thread; once ready it is a
        // style on the screen, one for both colors.
        val deadline = System.currentTimeMillis() + 5000
        var later = s.screen()
        while (later.children.none { it.tag == "style" } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            later = s.screen()
        }
        val glyphs = later.children.filter { it.tag == "style" }
        assertEquals(1, glyphs.size)
        val css = glyphs.single().attributes["_cssText"]!!
        assertTrue(css, css.startsWith(".ic.g0::before{") && css.contains("mask:url(data:image/png;base64,"))
        val classes = allNodes(later).mapNotNull { it.attributes["class"] }.filter { it.startsWith("ic") }.toList()
        assertEquals(listOf("ic g0", "ic g0"), classes)
        val png = android.util.Base64.decode(Regex("base64,([^)]+)\\)").find(css)!!.groupValues[1], 0)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun richTextAlignmentAndOverflow() {
        val s = RealScreen.open { a ->
            val rich = SpannableString("plain bold red big under").apply {
                setSpan(StyleSpan(Typeface.BOLD), 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.RED), 11, 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(2f), 15, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(UnderlineSpan(), 19, 24, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            column(
                a,
                TextView(a).apply { text = rich },
                sized(TextView(a).apply { text = "Centered"; gravity = Gravity.CENTER_HORIZONTAL }, 120, WRAP_CONTENT),
                sized(
                    TextView(a).apply {
                        text = "A line far too long for its box"
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    120, WRAP_CONTENT,
                ),
                sized(TextView(a).apply { text = "Two lines that wrap around in the narrow box" }, 120, WRAP_CONTENT),
                TextView(a).apply { text = "مرحبا بالعالم" },
                TextView(a).apply { text = "struck"; paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG },
            )
        }
        val screen = s.screen()
        val bold = allNodes(screen).first { it.tag == "span" && it.children.single().text == "bold" }
        assertTrue(style(bold), style(bold).contains("font-weight:700;"))
        val red = allNodes(screen).first { it.tag == "span" && it.children.single().text == "red" }
        assertTrue(style(red), style(red).contains("color:#ff0000;"))
        val big = allNodes(screen).first { it.tag == "span" && it.children.single().text == "big" }
        assertTrue(style(big), style(big).contains("font-size:28px;"))
        val under = allNodes(screen).first { it.tag == "span" && it.children.single().text == "under" }
        assertTrue(style(under), style(under).contains("text-decoration:underline;"))

        val centered = withText(screen, "Centered")!!
        assertEquals("t n", centered.attributes["class"])
        assertTrue(style(centered), style(centered).contains("justify-content:center;"))
        val cut = allNodes(screen).first { n -> !n.isText && n.children.any { it.isText && it.text!!.startsWith("A line far") } }
        assertEquals("t n e", cut.attributes["class"])
        val shown = cut.children.single().text!!
        assertTrue("the text the app shows, cut where it cuts it: $shown", shown.endsWith("…") && shown.length < 31)
        assertEquals("t", withText(screen, "Two lines that wrap around in the narrow box")!!.attributes["class"])
        val arabic = withText(screen, "مرحبا بالعالم")!!
        assertTrue(style(arabic), style(arabic).contains("direction:rtl;"))
        assertTrue(style(withText(screen, "struck")!!).contains("text-decoration:line-through;"))
    }

    @Test
    fun transformsFadesClipsAndTheOrderViewsDrawIn() {
        val s = RealScreen.open { a ->
            FrameLayout(a).apply {
                addView(View(a).apply { setBackgroundColor(Color.CYAN); rotation = 90f }, FrameLayout.LayoutParams(40, 20).apply { topMargin = 100 })
                addView(View(a).apply { setBackgroundColor(Color.YELLOW); scaleX = 2f }, FrameLayout.LayoutParams(40, 20).apply { topMargin = 200 })
                addView(
                    FrameLayout(a).apply {
                        alpha = 0.5f
                        addView(View(a).apply { setBackgroundColor(Color.MAGENTA) }, FrameLayout.LayoutParams(40, 20))
                    },
                    FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 300 },
                )
                // Listed first but lifted: drawn over the other one.
                addView(TextView(a).apply { text = "lifted"; elevation = 8f; setBackgroundColor(Color.WHITE) }, FrameLayout.LayoutParams(100, 40).apply { topMargin = 400 })
                addView(TextView(a).apply { text = "flat"; setBackgroundColor(Color.LTGRAY) }, FrameLayout.LayoutParams(100, 40).apply { topMargin = 400 })
                addView(
                    FrameLayout(a).apply {
                        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 12f }
                        clipToOutline = true
                        addView(View(a).apply { setBackgroundColor(Color.GREEN) }, FrameLayout.LayoutParams(80, 80))
                    },
                    FrameLayout.LayoutParams(60, 60).apply { topMargin = 500 },
                )
                addView(View(a).apply { setBackgroundColor(Color.BLUE); translationX = 25f }, FrameLayout.LayoutParams(10, 10).apply { topMargin = 600 })
            }
        }
        val screen = s.screen()
        val all = styles(screen)
        val rotated = all.first { it.contains("#00ffff") }
        assertTrue(rotated, rotated.contains("transform:matrix(0,1,-1,0,"))
        val scaled = all.first { it.contains("#ffff00") }
        assertTrue(scaled, scaled.contains("transform:matrix(2,0,0,1,"))
        val faded = allNodes(screen).first { style(it).contains("opacity:0.5;") }
        assertTrue(allNodes(faded).any { style(it).contains("#ff00ff") })
        assertEquals("by elevation, then as listed", listOf("flat", "lifted"), texts(screen))
        val clip = allNodes(screen).first { style(it).contains("background-color:#000000;") }
        assertTrue(style(clip), style(clip).contains("border-radius:12px;") && style(clip).contains("overflow:hidden;"))
        val moved = allNodes(screen).first { style(it).contains("#0000ff") }
        assertEquals(25f, position(screen, moved).first, 0.01f)
    }

    @Test
    fun materialComponentsAndBars() {
        lateinit var pay: MaterialButton
        lateinit var card: MaterialCardView
        val s = RealScreen.open { a ->
            pay = MaterialButton(a).apply { text = "Pay" }
            card = MaterialCardView(a).apply {
                radius = 16f
                cardElevation = 6f
                setCardBackgroundColor(Color.WHITE)
                addView(TextView(a).apply { text = "Card body"; setPadding(16, 16, 16, 16) })
                layoutParams = LinearLayout.LayoutParams(300, 100)
            }
            column(
                a,
                Toolbar(a).apply { title = "Checkout"; setBackgroundColor(0xFF6750A4.toInt()); setTitleTextColor(Color.WHITE) },
                TabLayout(a).apply {
                    addTab(newTab().setText("Cart"))
                    addTab(newTab().setText("Shipping"))
                },
                pay,
                MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Cancel" },
                Chip(a).apply { text = "Express" },
                card,
                FloatingActionButton(a).apply { setImageDrawable(ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_search_api_material)) },
                BottomNavigationView(a).apply {
                    menu.add(0, 1, 0, "Home")
                    menu.add(0, 2, 1, "Orders")
                },
            )
        }
        val screen = s.screen()
        val text = texts(screen)
        assertTrue(text.toString(), text.containsAll(listOf("Checkout", "Cart", "Shipping", "Pay", "Cancel", "Express", "Card body", "Home", "Orders")))
        // A Material button: its shape (read by drawing it) and its text, where it is.
        val payBox = ancestors(screen, withText(screen, "Pay")!!).first()
        assertAt(s, screen, payBox, pay)
        val payStyles = (listOf(payBox) + payBox.children).map(::style)
        assertTrue(payStyles.toString(), payStyles.any { it.contains("background-color:#6750a4;") && it.contains("border-radius:") })
        val cardBox = ancestors(screen, withText(screen, "Card body")!!).first { style(it).contains("background-color:#ffffff") }
        assertTrue(style(cardBox), style(cardBox).contains("border-radius:16px;"))
        assertTrue(style(cardBox), style(cardBox).contains("box-shadow:0 "))
        assertTrue("the outlined button has an outline", styles(screen).any { it.contains("inset 0 0 0 1px") })
    }

    @Test
    fun viewsTheWireframeCannotReadArePlaceholders() {
        val s = RealScreen.open { a ->
            column(
                a,
                sized(SurfaceView(a), MATCH_PARENT, 100),
                sized(VideoView(a), MATCH_PARENT, 90),
                sized(TextureView(a), MATCH_PARENT, 60),
                sized(FakeMapView(a), MATCH_PARENT, 120),
            )
        }
        val screen = s.screen()
        val labels = allNodes(screen).filter { it.attributes["class"] == "pv" }.map { it.children.single().text }.toList()
        // (A WebView is one too, but Robolectric's web view never takes any room.)
        assertEquals(listOf("Native view", "Video", "Video", "Map"), labels)
        val surface = withText(screen, "Native view")!!
        assertEquals(100f, styleNumber(surface, "height"))
        assertEquals(0f, position(screen, surface).second)
        assertEquals(250f, position(screen, withText(screen, "Map")!!).second)
    }

    @Test
    fun aViewThatDrawsItselfIsMarked() {
        val s = RealScreen.open { a -> FrameLayout(a).apply { addView(Sparkline(a), FrameLayout.LayoutParams(100, 30)) } }
        val screen = s.screen()
        val custom = allNodes(screen).single { it.attributes["class"] == "cp" }
        assertEquals(100f, styleNumber(custom, "width"))
    }

    @Test
    fun aViewThatFailsCostsItsOwnNodeNotTheFrame() {
        val s = RealScreen.open { a ->
            column(a, TextView(a).apply { text = "before" }, BrokenText(a).apply { text = "inside" }, TextView(a).apply { text = "after" })
        }
        val screen = s.screen()
        assertEquals(listOf("before", "after"), texts(screen))
        assertTrue(s.capture.lastError is IllegalStateException)
    }

    @Test
    fun aViewReplaysOnTheScreenInTheMirror() {
        lateinit var label: TextView
        lateinit var check: CheckBox
        val s = RealScreen.open { a ->
            label = TextView(a).apply { text = "Count 0" }
            check = CheckBox(a).apply { text = "Agree" }
            column(a, label, check, View(a).apply { setBackgroundColor(Color.RED); layoutParams = LinearLayout.LayoutParams(40, 40) })
        }
        var before = s.screen()
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        val steps = listOf<() -> Unit>(
            { label.text = "Count 1" },
            { check.isChecked = true },
            { label.visibility = View.GONE },
            { label.visibility = View.VISIBLE; label.text = "Count 2" },
            { check.isChecked = false; check.isEnabled = false },
            { (label.parent as ViewGroup).addView(TextView(s.activity).apply { text = "new row" }, 0) },
        )
        for ((i, step) in steps.withIndex()) {
            step()
            val after = s.screen()
            diffScreens(before, after)?.let { mirror.mutation(it) }
            assertEquals("step $i", dumpVNode(after), mirror.screen.dump())
            before = after
        }
        assertEquals(listOf("new row", "Count 2", "Agree"), mirror.texts())
    }

    /** A map SDK's view, by its name. */
    class FakeMapView(context: Context) : FrameLayout(context)

    /** A chart that draws itself. */
    class Sparkline(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLUE)
        }
    }

    /** A view whose own code fails while it is read. */
    class BrokenText(context: Context) : TextView(context) {
        override fun getTotalPaddingLeft(): Int = throw IllegalStateException("broken")
    }

    @Test
    fun aMaskedSubtreeHidesItsTextButKeepsItsShape() {
        val s = RealScreen.open { a ->
            FrameLayout(a).apply {
                Traceway.mask(this)
                addView(
                    column(
                        a,
                        TextView(a).apply { text = "Balance 1,234.56" },
                        sized(ImageView(a).apply { setImageDrawable(ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_search_api_material)) }, 24, 24),
                    ),
                )
            }
        }
        val screen = s.screen()
        assertEquals(listOf("******* ********"), texts(screen))
        assertNull("a masked icon gets no glyph", allNodes(screen).firstOrNull { (it.attributes["class"] ?: "").startsWith("ic g") })
    }
}
