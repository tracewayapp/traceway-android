package com.tracewayapp.traceway.wireframe

import android.content.Context
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.NumberPicker
import android.text.style.ClickableSpan
import android.graphics.drawable.StateListDrawable
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
import com.tracewayapp.traceway.tracewayMask
import android.graphics.Insets
import android.view.WindowInsets
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
 *
 * One test per topic of the wireframe suites every SDK shares (topics 1 to
 * 40, in their order; the iOS SDK's `UIKitWireframeTests` mirrors them;
 * topics 16 to 18 are in [WireframeImagesTest], 41 to 48 in
 * [ComposeWireframeTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewWireframeTest {
    /** Colours this class resolved in dark mode must not be served to the tests after it (see [resetAndroidxColorCaches]). */
    @org.junit.After
    fun forgetResolvedColours() = resetAndroidxColorCaches()

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
    fun boxesCornersBordersShadowsAndTheirPositions() {
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
    fun lengthsAreInDpAtAnotherDensity() {
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
    fun layoutOnlyContainersFoldIntoTheirChildren() {
        lateinit var deep: TextView
        val s = RealScreen.open { a ->
            FrameLayout(a).apply {
                addView(
                    FrameLayout(a).apply {
                        addView(
                            LinearLayout(a).apply {
                                deep = TextView(a).apply { text = "Deep" }
                                addView(deep, LinearLayout.LayoutParams(100, 20).apply { leftMargin = 5; topMargin = 6 })
                            },
                            FrameLayout.LayoutParams(200, 200).apply { leftMargin = 10; topMargin = 10 },
                        )
                    },
                    FrameLayout.LayoutParams(300, 300).apply { leftMargin = 40; topMargin = 50 },
                )
            }
        }
        val screen = s.screen()
        val text = withText(screen, "Deep")!!
        assertAt(s, screen, text, deep)
        // The window's background is an element; the three plain containers are not.
        val chain = ancestors(screen, text)
        assertTrue(chain.map(::style).toString(), chain.size <= 2)
        assertTrue(chain.none { style(it).startsWith("left:40px;top:50px") || style(it).startsWith("left:10px;top:10px") })
    }

    @Test
    fun whatIsNotPaintedIsLeftOut() {
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
                TextView(a).apply { text = "offscreen"; translationY = 2000f },
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
    fun opacityClipsAndTransforms() {
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
        assertTrue("a faded group fades what it holds", allNodes(faded).any { style(it).contains("#ff00ff") })
        val clip = allNodes(screen).first { style(it).contains("background-color:#000000;") }
        assertTrue(style(clip), style(clip).contains("border-radius:12px;") && style(clip).contains("overflow:hidden;"))
        val moved = allNodes(screen).first { style(it).contains("#0000ff") }
        assertEquals(25f, position(screen, moved).first, 0.01f)
    }

    @Test
    fun zOrderFollowsElevationThenOrder() {
        lateinit var lifted: TextView
        val s = RealScreen.open { a ->
            FrameLayout(a).apply {
                // Listed first but lifted: drawn over the other one.
                lifted = TextView(a).apply { text = "lifted"; elevation = 8f; setBackgroundColor(Color.WHITE) }
                addView(lifted, FrameLayout.LayoutParams(100, 40))
                addView(TextView(a).apply { text = "flat"; setBackgroundColor(Color.LTGRAY) }, FrameLayout.LayoutParams(100, 40))
            }
        }
        val before = s.screen()
        assertEquals("by elevation, then as listed", listOf("flat", "lifted"), texts(before))
        lifted.elevation = 0f
        val after = s.screen()
        assertEquals("as listed", listOf("lifted", "flat"), texts(after))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        mirror.mutation(diffScreens(before, after)!!)
        assertEquals(dumpVNode(after), mirror.screen.dump())
    }

    @Test
    fun aLabelKeepsItsBackground() {
        lateinit var badge: TextView
        val s = RealScreen.open { a ->
            badge = TextView(a).apply {
                text = "Badge"
                setBackgroundColor(0x80000000.toInt())
                setPadding(6, 4, 6, 4)
            }
            column(a, badge)
        }
        val screen = s.screen()
        val text = withText(screen, "Badge")!!
        val box = ancestors(screen, text).first()
        assertTrue(style(box), style(box).contains("background-color:rgba(0,0,0,0.502);"))
        assertAt(s, screen, box, badge)
        val (bx, by) = position(screen, box)
        val (tx, ty) = position(screen, text)
        assertEquals("the text inside the padding", 6f to 4f, (tx - bx) to (ty - by))
    }

    @Test
    fun labelsKeepLinesAlignmentFontsAndCutLines() {
        val s = RealScreen.open { a ->
            column(
                a,
                TextView(a).apply { text = "Bold title"; textSize = 20f; setTypeface(typeface, Typeface.BOLD) },
                TextView(a).apply {
                    text = "Medium title"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                },
                TextView(a).apply { text = "Code 0123"; typeface = Typeface.MONOSPACE },
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
                TextView(a).apply { text = "struck"; paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG },
            )
        }
        val screen = s.screen()
        val bold = withText(screen, "Bold title")!!
        assertTrue(style(bold), style(bold).contains("font-size:20px;") && style(bold).contains("font-weight:700;"))
        // Roboto Medium is written as the app set it, as the iOS and Flutter SDKs write their weights.
        val medium = withText(screen, "Medium title")!!
        assertTrue(style(medium), style(medium).contains("font-size:16px;") && style(medium).contains("font-weight:500;"))
        assertTrue(style(withText(screen, "Code 0123")!!).contains("font-family:monospace;"))
        val centered = withText(screen, "Centered")!!
        assertEquals("t n", centered.attributes["class"])
        assertTrue(style(centered), style(centered).contains("justify-content:center;"))
        val cut = allNodes(screen).first { n -> !n.isText && n.children.any { it.isText && it.text!!.startsWith("A line far") } }
        assertEquals("t n e", cut.attributes["class"])
        val shown = cut.children.single().text!!
        assertTrue("the text the app shows, cut where it cuts it: $shown", shown.endsWith("…") && shown.length < 31)
        val wrapped = withText(screen, "Two lines that wrap around in the narrow box")!!
        assertEquals("t", wrapped.attributes["class"])
        assertTrue("two lines' height: ${style(wrapped)}", styleNumber(wrapped, "height") > 1.8f * styleNumber(wrapped, "line-height"))
        assertTrue(style(withText(screen, "struck")!!).contains("text-decoration:line-through;"))
    }

    @Test
    fun richTextBecomesSpans() {
        val s = RealScreen.open { a ->
            val rich = SpannableString("plain bold red big under link").apply {
                setSpan(StyleSpan(Typeface.BOLD), 6, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.RED), 11, 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(RelativeSizeSpan(2f), 15, 18, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(UnderlineSpan(), 19, 24, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(object : ClickableSpan() { override fun onClick(widget: View) {} }, 25, 29, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            column(a, TextView(a).apply { text = rich; setLinkTextColor(Color.BLUE) })
        }
        val screen = s.screen()
        fun span(text: String) = allNodes(screen).first { it.tag == "span" && it.children.single().text == text }
        assertTrue(style(span("bold")), style(span("bold")).contains("font-weight:700;"))
        assertTrue(style(span("red")), style(span("red")).contains("color:#ff0000;"))
        assertTrue(style(span("big")), style(span("big")).contains("font-size:28px;"))
        assertTrue(style(span("under")), style(span("under")).contains("text-decoration:underline;"))
        assertTrue("a link in the link color, underlined: ${style(span("link"))}", style(span("link")).contains("color:#0000ff;") && style(span("link")).contains("text-decoration:underline;"))
        assertFalse(style(span("plain ")).contains("text-decoration"))
    }

    @Test
    fun rightToLeftTextAndLayoutDirection() {
        // As an app that declares android:supportsRtl.
        org.robolectric.RuntimeEnvironment.getApplication().applicationInfo.flags =
            org.robolectric.RuntimeEnvironment.getApplication().applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        lateinit var first: View
        lateinit var second: View
        lateinit var row: LinearLayout
        val s = RealScreen.open { a ->
            first = sized(TextView(a).apply { text = "First" }, 100, 30)
            second = sized(TextView(a).apply { text = "Second" }, 100, 30)
            row = LinearLayout(a).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                addView(first)
                addView(second)
            }
            column(
                a,
                row,
                TextView(a).apply { text = "مرحبا بالعالم" },
                sized(
                    TextView(a).apply {
                        text = "Start aligned"
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    },
                    200, WRAP_CONTENT,
                ),
                sized(SeekBar(a).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; max = 100; progress = 25 }, 200, 40),
            )
        }
        var screen = s.screen()
        // A right-to-left row lays its first child out on the right.
        assertEquals(300f, position(screen, withText(screen, "First")!!).first, 0.6f)
        assertEquals(200f, position(screen, withText(screen, "Second")!!).first, 0.6f)
        assertAt(s, screen, withText(screen, "First")!!, first)
        val arabic = withText(screen, "مرحبا بالعالم")!!
        assertTrue(style(arabic), style(arabic).contains("direction:rtl;"))
        val start = withText(screen, "Start aligned")!!
        assertTrue("the start of a right-to-left view is its right: ${style(start)}", style(start).contains("justify-content:right;"))
        val thumb = allNodes(screen).single { style(it).contains("width:20px;height:20px;border-radius:50%;") }
        assertTrue("a right-to-left slider fills from the right: ${style(thumb)}", styleNumber(thumb, "left") > 100f)

        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val before = screen
        screen = s.screen()
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        mirror.mutation(diffScreens(before, screen)!!)
        assertEquals(dumpVNode(screen), mirror.screen.dump())
        assertEquals(0f, position(screen, withText(screen, "First")!!).first, 0.6f)
    }

    @Test
    fun fieldsMaskTypedTextShowPlaceholdersAndBulletSecrets() {
        lateinit var field: TextInputEditText
        val s = RealScreen.open { a ->
            field = TextInputEditText(a).apply { setText("ada@example.com") }
            column(
                a,
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
                TextInputLayout(a).apply {
                    hint = "Email"
                    addView(field)
                },
            )
        }
        var screen = s.screen()
        val text = allText(screen)
        assertTrue(text, text.contains("***** ****"))
        assertFalse(text, text.contains("typed"))
        assertFalse(text, text.contains("hunter2"))
        assertTrue("password fields show dots: $text", text.contains("•••••••"))
        assertTrue(text, text.contains("••••"))
        assertTrue("a hint is not typed text: $text", text.contains("Your name"))
        assertFalse(text, text.contains("ada@"))
        assertTrue("a filled Material field floats its label: $text", texts(screen).contains("Email"))

        s.capture.maskAllInputs = false
        screen = s.screen()
        assertTrue(allText(screen), texts(screen).containsAll(listOf("typed text", "ada@example.com")))
        assertFalse("a password stays masked", allText(screen).contains("hunter2"))
        assertFalse(allText(screen).contains("1234"))
    }

    @Test
    fun fieldDecorations() {
        lateinit var email: TextInputEditText
        lateinit var city: TextInputEditText
        val s = RealScreen.open { a ->
            val outlined = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "Email"
                helperText = "We never share it"
            }
            email = TextInputEditText(outlined.context)
            outlined.addView(email)
            val filled = TextInputLayout(a, null, com.google.android.material.R.attr.textInputFilledStyle).apply { hint = "City" }
            city = TextInputEditText(filled.context)
            filled.addView(city)
            column(a, outlined, filled)
        }
        email.requestFocus()
        var screen = s.screen()
        assertTrue(allText(screen), texts(screen).containsAll(listOf("Email", "We never share it", "City")))
        assertTrue("an outlined box: ${styles(screen)}", styles(screen).any { Regex("box-shadow:inset 0 0 0 [12]px").containsMatchIn(it) })
        // A filled box is rounded at its top only (Material reads 4 dp).
        val filledBox = Regex("border-radius:([0-9.]+)px ([0-9.]+)px 0px 0px;")
        val radii = styles(screen).mapNotNull { filledBox.find(it)?.groupValues?.drop(1)?.map(String::toFloat) }
        assertTrue("the filled box: ${styles(screen)}", radii.any { r -> r.all { it in 3f..5.5f } })
        // An empty field that is not focused shows its label where its text goes, at the text's size.
        assertTrue(style(withText(screen, "City")!!), styleNumber(withText(screen, "City")!!, "font-size") > 14f)
        // A focused one floats it over the outline, at 12 px.
        val floated = withText(screen, "Email")!!
        assertTrue(style(floated), style(floated).contains("font-size:12px;"))
        assertEquals(onScreen(s, email).second - 7.2f, position(screen, floated).second, 1f)

        // Filled in, a filled box keeps its label inside, just above the text.
        city.setText("Berlin")
        screen = s.screen()
        val inside = withText(screen, "City")!!
        assertTrue(style(inside), style(inside).contains("font-size:12px;"))
        val (_, cityTop) = onScreen(s, city)
        assertEquals(cityTop + city.totalPaddingTop / s.density - 14.4f, position(screen, inside).second, 1f)
        assertFalse(allText(screen).contains("Berlin"))
    }

    @Test
    fun privacyMasksBlankAndBlur() {
        val s = RealScreen.open { a ->
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
                TextView(a).apply { text = "shown" },
            )
        }
        val screen = s.screen()
        val text = allText(screen)
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("tagged"))
        assertEquals("a blurred mask keeps the shape of words", listOf("****** ****", "shown"), texts(screen))
        val blank = allNodes(screen).first { style(it).contains("background:#123456;") }
        assertEquals("mk", blank.attributes["class"])
        assertEquals("both blank masks", 2, allNodes(screen).count { it.attributes["class"] == "mk" })
    }

    @Test
    fun aMaskedSubtreeHidesItsTextButKeepsItsShape() {
        lateinit var masked: FrameLayout
        val s = RealScreen.open { a ->
            FrameLayout(a).apply {
                masked = this
                tracewayMask = TracewayMaskMode.blur()
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

        // The property and the Java statics set the same mask.
        Traceway.unmask(masked)
        assertNull(masked.tracewayMask)
        assertEquals(listOf("Balance 1,234.56"), texts(s.screen()))
        Traceway.mask(masked)
        assertSame(TracewayMaskMode.Blur, masked.tracewayMask)
        masked.tracewayMask = null
        assertNull(masked.tracewayMask)
        masked.tag = "traceway-blank"
        assertTrue(masked.tracewayMask is TracewayMaskMode.Blank)
    }

    @Test
    fun imagesArePlaceholdersWhenOffAndIconsAreGlyphShapes() {
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
    fun togglesAreDrawnFromTheirState() {
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
                MaterialSwitch(a).apply { text = "Bluetooth" },
                ToggleButton(a).apply { isChecked = true },
            )
        }
        val screen = s.screen()
        val all = styles(screen)
        val text = texts(screen)
        assertTrue(text.toString(), text.containsAll(listOf("Remember me", "Off", "Card", "Framework switch", "Compat switch", "Wi-Fi", "Bluetooth", "ON")))
        // A checked box is filled and has a tick.
        assertTrue(all.joinToString("\n"), all.any { it.contains("width:18px;height:18px;border-radius:2px;background-color:") })
        assertTrue(all.any { it.startsWith("left:6px;top:2px;width:6px;height:11px;border:solid #fff") })
        assertTrue("an unchecked, disabled box is an outline", all.any { it.contains("border-radius:2px;box-shadow:inset 0 0 0 2px rgba(") })
        assertTrue("a checked radio has its dot", all.any { it.startsWith("left:5px;top:5px;width:10px;height:10px;border-radius:50%;") })
        // A Material 3 switch's track in the color the switch paints it (primary), not the theme's accent.
        assertTrue(all.joinToString("\n"), all.any { it.contains("width:52px;height:32px;border-radius:16px;background-color:#6750a4;") })
        assertTrue("off, it is outlined over its fill", all.any { it.contains("width:52px;height:32px;border-radius:16px;background-color:#") && it.contains("box-shadow:inset 0 0 0 2px #79747e;") })
        assertTrue("an off framework switch", all.any { it.contains("border-radius:7px;background-color:rgba(") })
        // The toggles sit where the views draw them.
        assertAt(s, screen, ancestors(screen, withText(screen, "Remember me")!!).first(), check)
        assertAt(s, screen, ancestors(screen, withText(screen, "Wi-Fi")!!).first(), material)
    }

    @Test
    fun slidersProgressAndSpinners() {
        val s = RealScreen.open { a ->
            column(
                a,
                sized(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 25 }, 200, 16),
                sized(ProgressBar(a).apply { isIndeterminate = true }, 48, 48),
                sized(SeekBar(a).apply { max = 10; progress = 5 }, 300, 40),
                sized(LinearProgressIndicator(a).apply { progress = 70 }, 300, WRAP_CONTENT),
            )
        }
        val screen = s.screen()
        val all = styles(screen)
        assertNotNull("the bar at a quarter", allNodes(screen).firstOrNull { style(it).startsWith("width:25%;height:100%;") })
        assertTrue("an indeterminate ring", all.any { it.contains("border-radius:50%;border:") && it.contains("border-top-color:") })
        assertTrue("the slider's thumb is half way", all.any { it.contains("width:20px;height:20px;border-radius:50%;") })
        // A Material indicator in the color it paints (the primary), not the theme's accent.
        assertTrue(all.joinToString("\n"), all.any { it.startsWith("width:70%;height:100%;background-color:#6750a4;") })
    }

    @Test
    fun segmentedControlsShowTheirTitlesAndSelection() {
        lateinit var group: MaterialButtonToggleGroup
        lateinit var buttons: List<MaterialButton>
        val s = RealScreen.open { a ->
            val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
            buttons = listOf("Day", "Week", "Month").map { t -> MaterialButton(a, null, outlined).apply { text = t; id = View.generateViewId() } }
            group = MaterialButtonToggleGroup(a).apply {
                isSingleSelection = true
                for (b in buttons) addView(b)
                check(buttons[1].id)
            }
            column(a, group)
        }
        fun box(screen: VNode, title: String) = ancestors(screen, withText(screen, title)!!).first()
        fun fill(screen: VNode, title: String) = (listOf(box(screen, title)) + box(screen, title).children).map(::style)
            .firstNotNullOfOrNull { Regex("background-color:([^;]+);").find(it)?.groupValues?.get(1) }
        var screen = s.screen()
        val xs = listOf("Day", "Week", "Month").map { position(screen, withText(screen, it)!!).first }
        assertEquals("side by side", xs.sorted(), xs)
        for ((i, b) in buttons.withIndex()) assertAt(s, screen, box(screen, listOf("Day", "Week", "Month")[i]), b)
        val checked = fill(screen, "Week")
        assertNotNull("the checked segment is filled", checked)
        assertTrue("the others are not filled like it: ${fill(screen, "Day")}", fill(screen, "Day") != checked)

        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(screen)))
        group.check(buttons[2].id)
        val after = s.screen()
        mirror.mutation(diffScreens(screen, after)!!)
        assertEquals(dumpVNode(after), mirror.screen.dump())
        assertEquals(checked, fill(after, "Month"))
        screen = after
        assertTrue(fill(screen, "Week") != checked)
    }

    @Test
    fun steppersShowTheirValue() {
        lateinit var picker: NumberPicker
        val s = RealScreen.open { a ->
            picker = NumberPicker(a).apply {
                minValue = 0
                maxValue = 10
                value = 4
            }
            column(a, RatingBar(a).apply { numStars = 5; stepSize = 1f; rating = 3f }, sized(picker, 80, 160))
        }
        var screen = s.screen()
        assertTrue(allText(screen), texts(screen).contains("★★★☆☆"))
        // The picker's value is in a field: masked like typed text unless inputs are shown.
        assertTrue(allText(screen), texts(screen).contains("*"))
        s.capture.maskAllInputs = false
        screen = s.screen()
        assertTrue(allText(screen), texts(screen).contains("4"))
        picker.value = 7
        assertTrue(allText(s.screen()), texts(s.screen()).contains("7"))
    }

    @Test
    fun pageDotsShowTheSelectedPage() {
        lateinit var dots: TabLayout
        val s = RealScreen.open { a ->
            dots = TabLayout(a).apply {
                tabMode = TabLayout.MODE_FIXED
                tabGravity = TabLayout.GRAVITY_CENTER
                setSelectedTabIndicatorColor(Color.TRANSPARENT)
                for (i in 0 until 4) {
                    addTab(
                        newTab().setCustomView(
                            View(a).apply {
                                background = StateListDrawable().apply {
                                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) })
                                    addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.LTGRAY) })
                                }
                                layoutParams = ViewGroup.LayoutParams(10, 10)
                            },
                        ),
                    )
                }
            }
            column(a, sized(dots, 200, 30))
        }
        fun dotsOf(screen: VNode) = allNodes(screen).filter { style(it).contains("border-radius:50%;") }
            .sortedBy { position(screen, it).first }.map { style(it).contains("background-color:#000000;") }.toList()
        var screen = s.screen()
        assertEquals(listOf(true, false, false, false), dotsOf(screen))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(screen)))
        dots.getTabAt(2)!!.select()
        val after = s.screen()
        mirror.mutation(diffScreens(screen, after)!!)
        assertEquals(dumpVNode(after), mirror.screen.dump())
        screen = after
        assertEquals(listOf(false, false, true, false), dotsOf(screen))
    }

    @Test
    fun buttonsShowTheirTitleAndBackground() {
        lateinit var pay: MaterialButton
        lateinit var card: MaterialCardView
        val s = RealScreen.open { a ->
            pay = MaterialButton(a).apply { text = "Pay" }
            card = MaterialCardView(a).apply {
                radius = 16f
                cardElevation = 6f
                setCardBackgroundColor(Color.WHITE)
                setStrokeColor(Color.RED)
                strokeWidth = 2
                addView(TextView(a).apply { text = "Card body"; setPadding(16, 16, 16, 16); setBackgroundColor(Color.YELLOW) })
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
        // The card's outline (its foreground) is drawn over its content, which fills the card.
        val cardOutline = cardBox.children.last()
        assertEquals(
            "left:0px;top:0px;width:300px;height:100px;border-radius:16px;box-shadow:inset 0 0 0 2px #ff0000;",
            style(cardOutline),
        )
        val content = cardBox.children.indexOfFirst { allText(it).contains("Card body") }
        assertTrue(dumpVNode(cardBox), content in 0 until cardBox.children.size - 1)
        assertTrue("the outlined button has an outline", styles(screen).any { it.contains("inset 0 0 0 1px") })
    }

    @Test
    fun scrollingMovesOneContentNode() {
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
    fun listsReplayAsTheyScroll() {
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
    fun gridsReplayAsTheyScroll() {
        lateinit var grid: RecyclerView
        val s = RealScreen.open { a ->
            grid = RecyclerView(a).apply {
                layoutManager = GridLayoutManager(a, 3)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(TextView(a).apply { layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, 100) }) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).text = "Tile $position"
                    }

                    override fun getItemCount() = 300
                }
            }
            grid
        }
        var before = s.screen()
        assertTrue(allText(before), texts(before).containsAll(listOf("Tile 0", "Tile 1", "Tile 2", "Tile 23")))
        val lm = grid.layoutManager as GridLayoutManager
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        for (step in 1..5) {
            grid.scrollBy(0, 230)
            val after = s.screen()
            mirror.mutation(diffScreens(before, after)!!)
            assertEquals("step $step", dumpVNode(after), mirror.screen.dump())
            before = after
        }
        // Three tiles a row, each where the grid lays it out.
        val first = lm.findFirstCompletelyVisibleItemPosition() / 3 * 3
        val row = (first until first + 3).map { position(before, withText(before, "Tile $it")!!) }
        assertEquals(1, row.map { it.second }.toSet().size)
        for ((i, at) in row.withIndex()) assertEquals(i * 400f / 3, at.first, 1f)
        assertAt(s, before, withText(before, "Tile ${first + 1}")!!, grid.findViewHolderForAdapterPosition(first + 1)!!.itemView)
    }

    @Test
    fun aHorizontalListInsideAVerticalScroll() {
        lateinit var strip: RecyclerView
        lateinit var page: ScrollView
        val s = RealScreen.open { a ->
            strip = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                        object : RecyclerView.ViewHolder(TextView(a).apply { layoutParams = RecyclerView.LayoutParams(120, 100) }) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).text = "Card $position"
                    }

                    override fun getItemCount() = 20
                }
            }
            page = ScrollView(a).apply {
                addView(column(a, sized(strip, MATCH_PARENT, 100), *Array(30) { i -> sized(TextView(a).apply { text = "Line $i" }, MATCH_PARENT, 50) }))
            }
            page
        }
        var before = s.screen()
        assertTrue(texts(before).containsAll(listOf("Card 0", "Card 3", "Line 0")))
        assertFalse(texts(before).contains("Card 6"))
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        fun step(what: String, change: () -> Unit) {
            change()
            val after = s.screen()
            mirror.mutation(diffScreens(before, after)!!)
            assertEquals(what, dumpVNode(after), mirror.screen.dump())
            before = after
        }
        step("sideways") { strip.scrollBy(360, 0) }
        assertTrue(allText(before), texts(before).contains("Card 6"))
        assertFalse(texts(before).contains("Card 0"))
        assertAt(s, before, withText(before, "Card 5")!!, strip.findViewHolderForAdapterPosition(5)!!.itemView)
        step("down") { page.scrollTo(0, 60) }
        // The page's content moves as one node, the strip with it.
        assertAt(s, before, withText(before, "Card 5")!!, strip.findViewHolderForAdapterPosition(5)!!.itemView)
        assertTrue(texts(before).contains("Line 10"))
    }

    @Test
    fun pushingAndPoppingAScreenReplaysCleanly() {
        val s = RealScreen.open { a -> column(a, TextView(a).apply { text = "Inbox" }, TextView(a).apply { text = "Message from Ada" }) }
        var before = s.screen()
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        fun step(what: String) {
            val after = s.screen()
            mirror.mutation(diffScreens(before, after)!!)
            assertEquals(what, dumpVNode(after), mirror.screen.dump())
            before = after
        }
        val detail = org.robolectric.Robolectric.buildActivity(TestActivity::class.java).setup()
        detail.get().setContentView(column(detail.get(), TextView(detail.get()).apply { text = "Hello from Ada" }))
        s.controller.pause().stop()
        s.activity.window.decorView.visibility = View.INVISIBLE // what a stopped activity's window is
        step("pushed")
        assertEquals(listOf("Hello from Ada"), mirror.texts())
        detail.pause().stop().destroy()
        s.activity.window.decorView.visibility = View.VISIBLE
        s.controller.restart().resume()
        step("popped")
        assertEquals(listOf("Inbox", "Message from Ada"), mirror.texts())
    }

    @Test
    fun tabsShowTheSelectedTab() {
        lateinit var tabs: TabLayout
        lateinit var bar: BottomNavigationView
        val s = RealScreen.open { a ->
            tabs = TabLayout(a).apply {
                addTab(newTab().setText("Cart"))
                addTab(newTab().setText("Shipping"))
            }
            bar = BottomNavigationView(a).apply {
                menu.add(0, 1, 0, "Home").setIcon(androidx.appcompat.R.drawable.abc_ic_search_api_material)
                menu.add(0, 2, 1, "Orders").setIcon(androidx.appcompat.R.drawable.abc_ic_search_api_material)
            }
            column(a, tabs, bar)
        }
        fun color(screen: VNode, t: String) = Regex("color:(#[0-9a-f]{6});").find(style(withText(screen, t)!!))!!.groupValues[1]
        var before = s.screen()
        val selected = color(before, "Cart")
        assertTrue("the selected tab's text differs: ${color(before, "Shipping")}", color(before, "Shipping") != selected)
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(before)))
        tabs.getTabAt(1)!!.select()
        bar.selectedItemId = 2
        val after = s.screen()
        mirror.mutation(diffScreens(before, after)!!)
        assertEquals(dumpVNode(after), mirror.screen.dump())
        assertEquals(selected, color(after, "Shipping"))
        assertTrue(texts(after).containsAll(listOf("Home", "Orders")))
        before = after
        // The tab's indicator sits under the selected tab.
        val shipping = ancestors(before, withText(before, "Shipping")!!)
        assertTrue(shipping.map(::style).toString(), allNodes(before).any { style(it).contains("background-color:") && styleNumber(it, "height") <= 4f })
    }

    @Test
    fun aDialogShowsOverThePageWithItsDim() {
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
    fun aBottomSheetShowsOverThePage() {
        val s = RealScreen.open { a -> column(a, TextView(a).apply { text = "Under the sheet" }) }
        lateinit var title: TextView
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(s.activity).apply {
            title = TextView(context).apply { text = "In the sheet" }
            setContentView(column(context, title, sized(TextView(context).apply { text = "Sheet row" }, MATCH_PARENT, 200)))
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.show()
        s.screen()
        val screen = s.screen()
        assertEquals(2, s.windows().size)
        val text = texts(screen)
        assertTrue(text.toString(), text.indexOf("Under the sheet") < text.indexOf("In the sheet"))
        assertTrue("the sheet dims the page", styles(screen).any { it.startsWith("left:0;top:0;width:400px;height:800px;background-color:rgba(0,0,0,") })
        assertAt(s, screen, withText(screen, "In the sheet")!!, title)
        sheet.dismiss()
        assertEquals(listOf("Under the sheet"), texts(s.screen()))
    }

    @Test
    fun aPopupSitsOverItsAnchor() {
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

    /** Shows the keyboard over the activity's window: an IME inset at its bottom, as the system gives it. */
    private fun showKeyboard(s: RealScreen, heightDp: Float?) {
        val decor = s.activity.window.decorView
        val insets = if (heightDp == null) {
            WindowInsets.Builder().build()
        } else {
            WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, s.px(heightDp)))
                .setVisible(WindowInsets.Type.ime(), true)
                .build()
        }
        val root = View::class.java.getMethod("getViewRootImpl").invoke(decor)!!
        root.javaClass.getDeclaredField("mLastWindowInsets").apply { isAccessible = true }.set(root, insets)
        assertEquals(heightDp != null, decor.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
    }

    private fun keyboardTest(): Pair<RealScreen, VNode> {
        val s = RealScreen.open { a -> column(a, EditText(a).apply { hint = "Name" }) }
        assertNull(withText(s.screen(), "Keyboard"))
        showKeyboard(s, 300f)
        val screen = s.screen()
        val keyboard = allNodes(screen).single { it.id == keyboardNodeId }
        val label = keyboard.children.single()
        assertEquals(keyboardLabelTextId, label.id)
        assertEquals("Keyboard", label.text)
        // Over everything else on the screen, at the bottom of the window, as wide as it.
        assertSame(keyboard, screen.children.last())
        val heightDp = s.activity.window.decorView.height / s.density
        assertEquals(heightDp - 300f, styleNumber(keyboard, "top"), 0.1f)
        assertEquals(400f, styleNumber(keyboard, "width"), 0.1f)
        assertEquals(300f, styleNumber(keyboard, "height"), 0.1f)
        assertSame("an unchanged keyboard is the same node", keyboard, allNodes(s.screen()).single { it.id == keyboardNodeId })
        return s to keyboard
    }

    @Test
    fun theKeyboardIsANeutralBox() {
        val (s, keyboard) = keyboardTest()
        assertTrue(style(keyboard), style(keyboard).endsWith(
            "background-color:#d1d3d9;color:#6d6d72;display:flex;align-items:center;justify-content:center;font-size:13px;",
        ))
        showKeyboard(s, null)
        assertTrue(allNodes(s.screen()).none { it.id == keyboardNodeId })
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    fun theKeyboardIsDarkInDarkMode() {
        val (_, keyboard) = keyboardTest()
        assertTrue(style(keyboard), style(keyboard).contains("background-color:#2c2c2e;color:#8e8e93;"))
    }

    @Test
    fun unchangedScreensShareTheirNodesAndAChangeIsOneText() {
        lateinit var status: TextView
        val s = RealScreen.open { a ->
            status = TextView(a).apply { text = "Loading" }
            column(a, sized(status, 200, 24), sized(TextView(a).apply { text = "Static" }, 200, 24))
        }
        val first = s.screen()
        val second = s.screen()
        assertSame("an unchanged screen is the previous frame's object", first, second)
        assertNull(diffScreens(first, second))
        status.text = "Done"
        val third = s.screen()
        val m = diffScreens(second, third)!!
        @Suppress("UNCHECKED_CAST")
        val changed = m["texts"] as List<Map<String, Any?>>
        assertEquals(listOf("Done"), changed.map { it["value"] })
        assertEquals(emptyList<Any>(), m["adds"])
        assertEquals(emptyList<Any>(), m["removes"])
        assertSame("the unchanged label is the same node", withText(second, "Static"), withText(third, "Static"))
    }

    @Test
    fun manyAndDeepViewsStayBounded() {
        val s = RealScreen.open { a ->
            val many = FrameLayout(a)
            for (i in 0 until 5000) {
                many.addView(View(a).apply { setBackgroundColor(Color.RED) }, FrameLayout.LayoutParams(5, 5).apply { leftMargin = i % 50 * 7; topMargin = i / 50 * 7 })
            }
            var deep: ViewGroup = FrameLayout(a)
            val top = deep
            for (i in 0 until 160) {
                val next = FrameLayout(a).apply { setBackgroundColor(if (i % 2 == 0) Color.BLUE else Color.CYAN) }
                deep.addView(next, FrameLayout.LayoutParams(100, 100))
                deep = next
            }
            FrameLayout(a).apply {
                addView(many)
                addView(top, FrameLayout.LayoutParams(100, 100))
            }
        }
        val screen = s.screen()
        assertTrue("${allNodes(screen).count()} nodes", allNodes(screen).count() <= 3200)
        assertSame("and still the same object when nothing changed", screen, s.screen())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-night-mdpi")
    fun darkModeResolvesDynamicColours() {
        val s = RealScreen.open(DayNightActivity::class.java) { a ->
            column(a, TextView(a).apply { text = "Dark"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
        }
        val screen = s.screen()
        val color = Regex("color:(#[0-9a-f]{6});").find(style(withText(screen, "Dark")!!))!!.groupValues[1]
        assertTrue("light text in the dark: $color", luminance(color) > 0.7f)
        val surface = styles(screen).mapNotNull { Regex("background-color:(#[0-9a-f]{6});").find(it)?.groupValues?.get(1) }.first()
        assertTrue("a dark surface: $surface", luminance(surface) < 0.2f)

        // The same day-night theme in the light (the app switched modes): its light colors, not the dark ones
        // read before. Themes are equal by their style, in either mode.
        val dark = s.capture.accent(s.activity.window.decorView)
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
        val light = org.robolectric.Robolectric.buildActivity(DayNightActivity::class.java).setup().get()
        assertEquals(s.activity.theme, light.theme)
        assertTrue("the light accent differs from the dark one", s.capture.accent(light.window.decorView) != dark)
    }

    private fun luminance(hex: String): Float {
        val c = Color.parseColor(hex)
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    }

    @Test
    fun nativeViewsAndCustomDrawingArePlaceholders() {
        val s = RealScreen.open { a ->
            column(
                a,
                sized(SurfaceView(a), MATCH_PARENT, 100),
                sized(VideoView(a), MATCH_PARENT, 90),
                sized(TextureView(a), MATCH_PARENT, 60),
                sized(FakeMapView(a), MATCH_PARENT, 120),
                sized(Sparkline(a), 100, 30),
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
        val custom = allNodes(screen).single { it.attributes["class"] == "cp" }
        assertEquals(100f, styleNumber(custom, "width"))
        assertEquals(370f, position(screen, custom).second)
    }

    @Test
    fun aViewThatThrowsCostsItsOwnNodeNotTheFrame() {
        val s = RealScreen.open { a ->
            column(a, TextView(a).apply { text = "before" }, BrokenText(a).apply { text = "inside" }, TextView(a).apply { text = "after" })
        }
        val screen = s.screen()
        assertEquals(listOf("before", "after"), texts(screen))
        assertTrue(s.capture.lastError is IllegalStateException)
    }

    @Test
    fun viewEditsReplayInTheMirror() {
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

    @Test
    fun theScreenNamesAndroidsFontStack() {
        val s = RealScreen.open { a -> column(a, TextView(a).apply { text = "Hello" }) }
        val screen = s.screen()
        assertEquals("tw", screen.attributes["id"])
        assertTrue(style(screen), style(screen).endsWith(";font-family:Roboto,\"Segoe UI\",\"Helvetica Neue\",Arial,sans-serif"))
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
}
