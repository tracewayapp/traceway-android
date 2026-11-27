package com.tracewayapp.traceway.wireframe

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayMaskMode
import org.robolectric.RuntimeEnvironment
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The scenarios of the wireframe gallery, in the order it visits them, the
 * same names and states as the iOS SDK's: one screen of real views each
 * (Compose for `declarative`), taken through its states by taps, typing,
 * drags and presentations. Every text a scenario shows is its own, so the
 * checker can find it in the replay.
 */
internal object GalleryScenes {
    val all: List<GalleryScenario> by lazy {
        listOf(
            layout, positioning, transforms, clipping, stacks, constraints, text, richText, buttons, controls,
            forms, keyboard, images, imagesOff, lists, grids, carousel, navigation, tabs, dialogs, sheets, menus,
            privacy, declarative, native, motion, rtl, darkMode,
        )
    }

    // ---- 1. layout -------------------------------------------------------------------

    private val layout = GalleryScenario("layout") {
        lateinit var fill: TextView
        lateinit var gone: TextView
        lateinit var invisible: TextView
        lateinit var transparent: TextView
        open { a ->
            val p = Page(a, "Layout", scrolls = true)
            fun shaped(text: String, textColor: Int = Color.BLACK, setup: GradientDrawable.() -> Unit) =
                label(a, text, 14f, textColor, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply(setup)
                }.lp(170, 70)
            fill = label(a, "Fill", 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF1E88E5.toInt())
            }.lp(170, 70)
            p.row(fill, shaped("Border") { setColor(Color.WHITE); setStroke(a.dp(2), 0xFFE53935.toInt()) })
            val bottomBorder = label(a, "Bottom border", 14f, Color.BLACK, bold = true).apply {
                gravity = Gravity.CENTER
                background = LayerDrawable(arrayOf(ColorDrawable(0xFF6D4C41.toInt()), ColorDrawable(0xFFFFF3E0.toInt()))).apply {
                    setLayerInset(1, 0, 0, 0, a.dp(4))
                }
            }.lp(170, 70)
            p.row(bottomBorder, shaped("Rounded", Color.WHITE) { setColor(0xFF43A047.toInt()); cornerRadius = a.dp(16).toFloat() })
            val r = a.dp(28).toFloat()
            val elevated = label(a, "Elevation", 14f, Color.BLACK, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = a.dp(12).toFloat() }
                elevation = a.dp(8).toFloat()
            }.lp(170, 70)
            p.row(shaped("Corners", Color.WHITE) { setColor(0xFF8E24AA.toInt()); cornerRadii = floatArrayOf(r, r, 0f, 0f, r, r, 0f, 0f) }, elevated)
            p.row(
                shaped("Linear") {
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    colors = intArrayOf(0xFFFF4081.toInt(), 0xFFFFC107.toInt())
                },
                shaped("Radial") {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = a.dp(90).toFloat()
                    colors = intArrayOf(Color.WHITE, 0xFF00897B.toInt())
                },
            )
            gone = label(a, "Gone text").apply { visibility = View.GONE }
            invisible = label(a, "Invisible text").apply { visibility = View.INVISIBLE }
            transparent = label(a, "Zero alpha text").apply { alpha = 0f }
            p.row(
                label(a, "Half opacity", 14f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(0xFF3949AB.toInt())
                    alpha = 0.5f
                }.lp(170, 50),
                gone, invisible, transparent,
            )
            p.add(label(a, "Visible text"))
            p.view
        }
        checkpoint(
            shows = listOf("Layout", "Fill", "Border", "Bottom border", "Rounded", "Corners", "Elevation", "Linear", "Radial", "Half opacity", "Visible text"),
            hides = listOf("Gone text", "Invisible text", "Zero alpha text"),
        )
        gone.visibility = View.VISIBLE
        transparent.alpha = 1f
        fill.setBackgroundColor(0xFFF4511E.toInt())
        checkpoint("changed", shows = listOf("Gone text", "Zero alpha text"), hides = listOf("Invisible text"))
    }

    // ---- 2. positioning ---------------------------------------------------------------

    private val positioning = GalleryScenario("positioning") {
        lateinit var raised: TextView
        open { a ->
            val p = Page(a, "Positioning", scrolls = true)
            val grid = FrameLayout(a).apply { setBackgroundColor(0xFFECEFF1.toInt()) }
            val places = listOf(
                "TL" to (Gravity.TOP or Gravity.LEFT), "TC" to (Gravity.TOP or Gravity.CENTER_HORIZONTAL), "TR" to (Gravity.TOP or Gravity.RIGHT),
                "CL" to (Gravity.CENTER_VERTICAL or Gravity.LEFT), "CC" to Gravity.CENTER, "CR" to (Gravity.CENTER_VERTICAL or Gravity.RIGHT),
                "BL" to (Gravity.BOTTOM or Gravity.LEFT), "BC" to (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL), "BR" to (Gravity.BOTTOM or Gravity.RIGHT),
            )
            for ((t, g) in places) {
                grid.addView(
                    label(a, t, 14f, Color.BLACK).apply { setBackgroundColor(0xFFFFE082.toInt()); setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2)) },
                    FrameLayout.LayoutParams(WRAP, WRAP, g),
                )
            }
            p.add(grid, h = 140)
            fun inset(text: String, margin: Int, padding: Int): FrameLayout = FrameLayout(a).apply {
                setBackgroundColor(0xFFB3E5FC.toInt())
                setPadding(a.dp(padding), a.dp(padding), a.dp(padding), a.dp(padding))
                addView(label(a, text, 13f, Color.BLACK).apply { setBackgroundColor(0xFF4FC3F7.toInt()) })
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(a.dp(margin), a.dp(margin), a.dp(margin), a.dp(margin)) }
            }
            p.row(inset("Pad 12", 0, 12), inset("Margin 12", 12, 0), inset("Both 8", 8, 8), spacing = 0)
            var inner: View = label(a, "Level 4", 13f, Color.WHITE, bold = true).apply { setBackgroundColor(0xFF263238.toInt()) }
            val shades = intArrayOf(0xFFCFD8DC.toInt(), 0xFFB0BEC5.toInt(), 0xFF90A4AE.toInt())
            for (level in 3 downTo 1) {
                val f = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(shades[level - 1])
                    setPadding(a.dp(4), a.dp(2), a.dp(8), a.dp(8))
                    addView(label(a, "Level $level", 12f, Color.BLACK))
                    addView(inner, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = a.dp(14) })
                }
                inner = f
            }
            p.add(inner, w = WRAP)
            val holder = FrameLayout(a).apply {
                setBackgroundColor(0xFFD1C4E9.toInt())
                addView(
                    label(a, "Half outside", 14f, Color.WHITE).apply { setBackgroundColor(0xFF7E57C2.toInt()); gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(a.dp(120), a.dp(36)).apply { leftMargin = -a.dp(60); topMargin = a.dp(12) },
                )
            }
            p.add(holder, w = 220, h = 60, start = 80)
            p.add(
                label(a, "Translated", 14f, Color.BLACK).apply {
                    setBackgroundColor(0xFFC8E6C9.toInt())
                    translationX = a.dp(40).toFloat()
                    translationY = a.dp(6).toFloat()
                },
                w = WRAP,
            )
            val stage = FrameLayout(a)
            raised = label(a, "Raised card", 15f, Color.BLACK, bold = true).apply {
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = a.dp(10).toFloat() }
                elevation = a.dp(8).toFloat()
            }
            val flat = label(a, "Flat card", 15f, Color.BLACK, bold = true).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
                setBackgroundColor(0xFFFFCCBC.toInt())
            }
            stage.addView(raised, FrameLayout.LayoutParams(a.dp(190), a.dp(70)))
            stage.addView(flat, FrameLayout.LayoutParams(a.dp(190), a.dp(70)).apply { leftMargin = a.dp(110); topMargin = a.dp(34) })
            p.add(stage, h = 120, top = 8)
            p.view
        }
        checkpoint(shows = listOf("TL", "TC", "TR", "CL", "CC", "CR", "BL", "BC", "BR", "Pad 12", "Margin 12", "Both 8", "Level 4", "Half outside", "Translated", "Raised card", "Flat card"))
        // Without its elevation the raised card draws in list order: under the flat one.
        raised.elevation = 0f
        checkpoint("moved", shows = listOf("Raised card", "Flat card"))
    }

    // ---- 3. transforms ----------------------------------------------------------------

    private val transforms = GalleryScenario("transforms") {
        lateinit var turning: TextView
        open { a ->
            val p = Page(a, "Transforms", scrolls = true)
            fun cell(text: String, color: Int, w: Int = 92, h: Int = 36, setup: TextView.() -> Unit): FrameLayout = FrameLayout(a).apply {
                addView(
                    label(a, text, 13f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        setBackgroundColor(color)
                        setup()
                    },
                    FrameLayout.LayoutParams(a.dp(w), a.dp(h), Gravity.CENTER),
                )
            }.lp(120, 104)
            p.row(
                cell("Rotate 15", 0xFFE53935.toInt()) { rotation = 15f; turning = this },
                cell("Rotate 45", 0xFFD81B60.toInt()) { rotation = 45f },
                cell("Rotate 90", 0xFF8E24AA.toInt()) { rotation = 90f },
                spacing = 6,
            )
            p.row(
                cell("Rotate 180", 0xFF5E35B1.toInt()) { rotation = 180f },
                cell("Half size", 0xFF3949AB.toInt()) { scaleX = 0.5f; scaleY = 0.5f },
                cell("Double", 0xFF1E88E5.toInt(), w = 54, h = 24) { scaleX = 2f; scaleY = 2f; textSize = 10f },
                spacing = 6,
            )
            p.row(
                cell("Corner half", 0xFF00897B.toInt()) { pivotX = 0f; pivotY = 0f; scaleX = 0.5f; scaleY = 0.5f },
                cell("Spin and grow", 0xFF43A047.toInt()) { rotation = 30f; scaleX = 1.2f; scaleY = 1.2f },
                cell("Mirrored", 0xFFF4511E.toInt()) { scaleX = -1f },
                spacing = 6,
            )
            val nested = FrameLayout(a).apply {
                setBackgroundColor(0xFFFFE0B2.toInt())
                rotation = 20f
                addView(
                    label(a, "Nested turn", 13f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        setBackgroundColor(0xFF6D4C41.toInt())
                        rotation = 20f
                    },
                    FrameLayout.LayoutParams(a.dp(100), a.dp(32), Gravity.CENTER),
                )
            }
            p.add(FrameLayout(a).apply { addView(nested, FrameLayout.LayoutParams(a.dp(150), a.dp(80), Gravity.CENTER)) }, h = 130)
            p.view
        }
        checkpoint(shows = listOf("Rotate 15", "Rotate 45", "Rotate 90", "Rotate 180", "Half size", "Double", "Corner half", "Spin and grow", "Mirrored", "Nested turn"))
        turning.rotation = 30f
        turning.text = "Rotate 30"
        checkpoint("changed", shows = listOf("Rotate 30"), hides = listOf("Rotate 15"))
    }

    // ---- 4. clipping ------------------------------------------------------------------

    private val clipping = GalleryScenario("clipping") {
        lateinit var strip: HorizontalScrollView
        open { a ->
            val p = Page(a, "Clipping", scrolls = true)
            val clipOn = FrameLayout(a).apply {
                setBackgroundColor(0xFFE3F2FD.toInt())
                addView(
                    label(a, "Clipped where the box ends, this line runs on", 14f, Color.BLACK).apply { maxLines = 1 },
                    FrameLayout.LayoutParams(a.dp(330), WRAP, Gravity.CENTER_VERTICAL).apply { leftMargin = a.dp(8) },
                )
            }
            p.add(clipOn, w = 190, h = 52)
            val spill = LinearLayout(a).apply {
                clipChildren = false
                addView(
                    FrameLayout(a).apply {
                        setBackgroundColor(0xFFFFF3E0.toInt())
                        addView(
                            label(a, "Spills out of its box unclipped", 14f, Color.BLACK).apply { maxLines = 1 },
                            FrameLayout.LayoutParams(a.dp(260), WRAP, Gravity.CENTER_VERTICAL).apply { leftMargin = a.dp(8) },
                        )
                    },
                    LinearLayout.LayoutParams(a.dp(150), a.dp(52)),
                )
            }
            p.add(spill, h = 52)
            fun rounded(text: String, w: Int, h: Int, radius: Int, color: Int): FrameLayout = FrameLayout(a).apply {
                background = GradientDrawable().apply { setColor(color); cornerRadius = a.dp(radius).toFloat() }
                clipToOutline = true
                addView(View(a).apply { setBackgroundColor(0xFF263238.toInt()) }, FrameLayout.LayoutParams(a.dp(70), a.dp(70)).apply { leftMargin = -a.dp(20); topMargin = -a.dp(20) })
                addView(
                    label(a, text, 14f, Color.WHITE, bold = true).apply { maxLines = 1 },
                    FrameLayout.LayoutParams(a.dp(w + 60), WRAP, Gravity.CENTER_VERTICAL).apply { leftMargin = a.dp(56) },
                )
            }.lp(w, h)
            p.row(rounded("Rounded clip", 160, 80, 24, 0xFF26A69A.toInt()), rounded("Capsule clip keeps ends round", 190, 48, 24, 0xFF5C6BC0.toInt()))
            strip = HorizontalScrollView(a).apply {
                addView(
                    LinearLayout(a).apply {
                        for (i in 1..6) {
                            addView(
                                FrameLayout(a).apply {
                                    setBackgroundColor(if (i % 2 == 0) 0xFFFFCDD2.toInt() else 0xFFF8BBD0.toInt())
                                    addView(
                                        label(a, "Scroll box $i, cut at its edge", 14f, Color.BLACK).apply { maxLines = 1 },
                                        FrameLayout.LayoutParams(a.dp(220), WRAP, Gravity.CENTER_VERTICAL).apply { leftMargin = a.dp(8) },
                                    )
                                },
                                LinearLayout.LayoutParams(a.dp(150), a.dp(70)).apply { marginEnd = a.dp(10) },
                            )
                        }
                    },
                )
            }
            p.add(strip, h = 70, top = 8)
            p.view
        }
        checkpoint(shows = listOf("Clipping", "Spills out of its box unclipped", "Rounded clip", "Capsule clip", "Scroll box 1", "Scroll box 2"))
        scroll(strip, dxDp = 320f)
        expect(strip.scrollX > activity.dp(200), "the strip scrolled: ${strip.scrollX}")
        checkpoint("scrolled", shows = listOf("Scroll box 4"), hides = listOf("Scroll box 1"))
    }

    // ---- 5. stacks ----------------------------------------------------------------------

    private val stacks = GalleryScenario("stacks") {
        lateinit var first: TextView
        lateinit var centered: LinearLayout
        open { a ->
            val p = Page(a, "Stacks", scrolls = true)
            fun part(text: String, color: Int, weight: Float) = label(a, text, 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(0, a.dp(48), weight)
            }
            first = part("One part", 0xFF1E88E5.toInt(), 1f)
            p.add(LinearLayout(a).apply { addView(first); addView(part("Two parts", 0xFF43A047.toInt(), 2f)); addView(part("One more", 0xFFFB8C00.toInt(), 1f)) })
            centered = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(0xFFF3E5F5.toInt())
                addView(label(a, "Centered A", 15f, Color.BLACK))
                addView(label(a, "Centered B is longer", 15f, Color.BLACK))
            }
            p.add(centered)
            p.add(
                LinearLayout(a).apply {
                    gravity = Gravity.END
                    setBackgroundColor(0xFFE0F2F1.toInt())
                    addView(label(a, "End A", 15f, Color.BLACK).apply { setBackgroundColor(0xFF80CBC4.toInt()) })
                    addView(label(a, "End B", 15f, Color.BLACK).apply { setBackgroundColor(0xFF4DB6AC.toInt()) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = a.dp(16) })
                },
            )
            p.add(
                LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFFFFFDE7.toInt())
                    for ((t, h) in listOf("Short" to 32, "Tall" to 72, "Mid" to 48)) {
                        addView(
                            label(a, t, 14f, Color.BLACK).apply { gravity = Gravity.CENTER; setBackgroundColor(0xFFFFF176.toInt()) },
                            LinearLayout.LayoutParams(a.dp(90), a.dp(h)).apply { marginEnd = a.dp(10) },
                        )
                    }
                },
            )
            p.add(
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                    dividerDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(0, a.dp(12)) }
                    for (i in 1..3) addView(label(a, "Spaced $i", 15f, Color.BLACK).apply { setBackgroundColor(0xFFE8EAF6.toInt()) })
                },
            )
            p.view
        }
        checkpoint(shows = listOf("One part", "Two parts", "One more", "Centered A", "Centered B is longer", "End A", "End B", "Short", "Tall", "Mid", "Spaced 1", "Spaced 3"))
        (first.layoutParams as LinearLayout.LayoutParams).weight = 3f
        first.requestLayout()
        centered.gravity = Gravity.END
        checkpoint("changed", shows = listOf("One part", "Centered A"))
    }

    // ---- 6. constraints -----------------------------------------------------------------

    private val constraints = GalleryScenario("constraints") {
        lateinit var layoutView: ConstraintLayout
        lateinit var biased: TextView
        open { a ->
            val p = Page(a, "Constraints")
            val cl = ConstraintLayout(a).apply { setBackgroundColor(0xFFF5F5F5.toInt()) }
            layoutView = cl
            fun tag(text: String, color: Int) = label(a, text, 14f, Color.BLACK).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                setPadding(a.dp(8), a.dp(6), a.dp(8), a.dp(6))
                setBackgroundColor(color)
                cl.addView(this)
            }
            val spread = listOf(tag("Spread A", 0xFFBBDEFB.toInt()), tag("Spread B", 0xFFBBDEFB.toInt()), tag("Spread C", 0xFFBBDEFB.toInt()))
            val packed = listOf(tag("Packed A", 0xFFC8E6C9.toInt()), tag("Packed B", 0xFFC8E6C9.toInt()))
            biased = tag("Bias 20%", 0xFFFFE0B2.toInt())
            val half = tag("Half width", 0xFFE1BEE7.toInt())
            val ratio = tag("Ratio 16:9", 0xFFB2EBF2.toInt())
            val centered = tag("Centered", 0xFFFFF9C4.toInt())
            val start = tag("Pinned start", 0xFFD7CCC8.toInt())
            val end = tag("Pinned end", 0xFFD7CCC8.toInt())
            val set = ConstraintSet()
            set.clone(cl)
            val parent = ConstraintSet.PARENT_ID
            val m = a.dp(12)
            for (v in spread + packed + listOf(biased, centered, start, end)) {
                set.constrainWidth(v.id, ConstraintSet.WRAP_CONTENT)
                set.constrainHeight(v.id, ConstraintSet.WRAP_CONTENT)
            }
            for (v in spread) set.connect(v.id, ConstraintSet.TOP, parent, ConstraintSet.TOP, m)
            set.createHorizontalChain(parent, ConstraintSet.LEFT, parent, ConstraintSet.RIGHT, spread.map { it.id }.toIntArray(), null, ConstraintSet.CHAIN_SPREAD)
            for (v in packed) set.connect(v.id, ConstraintSet.TOP, spread[0].id, ConstraintSet.BOTTOM, m)
            set.createHorizontalChain(parent, ConstraintSet.LEFT, parent, ConstraintSet.RIGHT, packed.map { it.id }.toIntArray(), null, ConstraintSet.CHAIN_PACKED)
            set.connect(biased.id, ConstraintSet.TOP, packed[0].id, ConstraintSet.BOTTOM, m)
            set.connect(biased.id, ConstraintSet.START, parent, ConstraintSet.START)
            set.connect(biased.id, ConstraintSet.END, parent, ConstraintSet.END)
            set.setHorizontalBias(biased.id, 0.2f)
            set.constrainWidth(half.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(half.id, ConstraintSet.WRAP_CONTENT)
            set.constrainPercentWidth(half.id, 0.5f)
            set.connect(half.id, ConstraintSet.TOP, biased.id, ConstraintSet.BOTTOM, m)
            set.connect(half.id, ConstraintSet.START, parent, ConstraintSet.START)
            set.constrainWidth(ratio.id, ConstraintSet.MATCH_CONSTRAINT)
            set.constrainHeight(ratio.id, ConstraintSet.MATCH_CONSTRAINT)
            set.connect(ratio.id, ConstraintSet.TOP, half.id, ConstraintSet.BOTTOM, m)
            set.connect(ratio.id, ConstraintSet.START, parent, ConstraintSet.START, a.dp(60))
            set.connect(ratio.id, ConstraintSet.END, parent, ConstraintSet.END, a.dp(60))
            set.setDimensionRatio(ratio.id, "16:9")
            set.connect(centered.id, ConstraintSet.TOP, ratio.id, ConstraintSet.BOTTOM)
            set.connect(centered.id, ConstraintSet.BOTTOM, start.id, ConstraintSet.TOP)
            set.connect(centered.id, ConstraintSet.START, parent, ConstraintSet.START)
            set.connect(centered.id, ConstraintSet.END, parent, ConstraintSet.END)
            set.connect(start.id, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM, m)
            set.connect(start.id, ConstraintSet.START, parent, ConstraintSet.START, m)
            set.connect(end.id, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM, m)
            set.connect(end.id, ConstraintSet.END, parent, ConstraintSet.END, m)
            set.applyTo(cl)
            p.add(cl, h = 500)
            p.view
        }
        checkpoint(shows = listOf("Spread A", "Spread B", "Spread C", "Packed A", "Packed B", "Bias 20%", "Half width", "Ratio 16:9", "Centered", "Pinned start", "Pinned end"))
        val set = ConstraintSet()
        set.clone(layoutView)
        set.setHorizontalBias(biased.id, 0.8f)
        set.applyTo(layoutView)
        biased.text = "Bias 80%"
        checkpoint("changed", shows = listOf("Bias 80%"), hides = listOf("Bias 20%"))
    }

    // ---- 7. text ----------------------------------------------------------------------

    private val text = GalleryScenario("text") {
        lateinit var growing: TextView
        open { a ->
            val p = Page(a, "Text", scrolls = true)
            p.row(label(a, "Caption 12", 12f), label(a, "Body 16", 16f), label(a, "Title 24", 24f), spacing = 14)
            growing = p.add(label(a, "Display 34", 34f))
            p.row(
                label(a, "Light 300").apply { typeface = Typeface.create(Typeface.DEFAULT, 300, false) },
                label(a, "Medium 500").apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) },
                label(a, "Bold 700").apply { typeface = Typeface.create(Typeface.DEFAULT, 700, false) },
                label(a, "Black 900").apply { typeface = Typeface.create(Typeface.DEFAULT, 900, false) },
            )
            p.row(
                label(a, "Italic text").apply { setTypeface(Typeface.DEFAULT, Typeface.ITALIC) },
                label(a, "Monospace 0123").apply { typeface = Typeface.MONOSPACE },
            )
            for ((t, g) in listOf("Aligned start" to Gravity.START, "Aligned center" to Gravity.CENTER_HORIZONTAL, "Aligned end" to Gravity.END)) {
                p.add(label(a, t, 15f).apply { gravity = g; setBackgroundColor(0xFFF1F8E9.toInt()) }, bottom = 4)
            }
            p.add(label(a, "Wrapping: this paragraph is long enough to wrap onto several lines inside the column of the page.", 15f), top = 6)
            p.add(label(a, "Cut at the end of a line that is much too long to fit in it", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            p.add(label(a, "Cut in the middle of a line that is much too long to fit", 15f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE })
            p.add(label(a, "Cut at the start of a line that is much too long to fit", 15f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.START })
            p.add(label(a, "Loose lines: a line spacing of 1.6 spreads these wrapped lines apart for reading.", 15f).apply { setLineSpacing(0f, 1.6f) })
            p.add(
                label(a, "Two lines at most: everything after the second line of this paragraph is cut off with an ellipsis by the text view.", 15f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            p.view
        }
        checkpoint(
            shows = listOf(
                "Caption 12", "Body 16", "Title 24", "Display 34", "Light 300", "Medium 500", "Bold 700", "Black 900", "Italic text",
                "Monospace 0123", "Aligned start", "Aligned center", "Aligned end", "Wrapping: this paragraph", "Cut at the end", "Loose lines",
                "Two lines at most",
            ),
        )
        growing.text = "Display grew"
        growing.textSize = 40f
        checkpoint("changed", shows = listOf("Display grew"), hides = listOf("Display 34"))
    }

    // ---- 8. rich-text -------------------------------------------------------------------

    private val richText = GalleryScenario("rich-text") {
        lateinit var link: TextView
        lateinit var status: TextView
        open { a ->
            val p = Page(a, "Rich text", scrolls = true)
            fun spanned(text: String, vararg spans: Triple<Any, Int, Int>) = SpannableString(text).apply {
                for ((span, from, to) in spans) setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            p.add(label(a, spanned("Red and blue words", Triple(ForegroundColorSpan(Color.RED), 0, 3), Triple(ForegroundColorSpan(Color.BLUE), 8, 12)), 18f))
            p.add(
                label(
                    a,
                    spanned(
                        "Bold italic underline strike",
                        Triple(StyleSpan(Typeface.BOLD), 0, 4), Triple(StyleSpan(Typeface.ITALIC), 5, 11),
                        Triple(UnderlineSpan(), 12, 21), Triple(StrikethroughSpan(), 22, 28),
                    ),
                    18f,
                ),
            )
            p.add(
                label(
                    a,
                    spanned("Highlighted background and monospace", Triple(BackgroundColorSpan(0xFFFFEB3B.toInt()), 0, 11), Triple(TypefaceSpan("monospace"), 27, 36)),
                    18f,
                ),
            )
            p.add(label(a, spanned("Big and small sizes", Triple(RelativeSizeSpan(1.6f), 0, 3), Triple(AbsoluteSizeSpan(11, true), 8, 13)), 18f))
            status = label(a, "No link tapped yet", 15f)
            link = label(
                a,
                spanned(
                    "Read the docs link here",
                    Triple(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                status.text = "Link tapped"
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                            }
                        },
                        9, 18,
                    ),
                ),
                18f,
            ).apply { movementMethod = LinkMovementMethod.getInstance() }
            p.add(link)
            p.add(status)
            p.view
        }
        checkpoint(shows = listOf("Red", "blue", "Bold", "strike", "Highlighted", "monospace", "Big", "small", "docs link", "No link tapped yet"))
        // The middle of "docs link", where its layout draws it.
        val layout = link.layout
        val offset = 13
        val line = layout.getLineForOffset(offset)
        val x = link.totalPaddingLeft + layout.getPrimaryHorizontal(offset)
        val y = link.totalPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        val r = onScreenPx(link, RectF(x, y, x, y))
        tapAt(r.left, r.top)
        expect(status.text == "Link tapped", "the tap on the link reached it")
        checkpoint("changed", shows = listOf("Link tapped"), hides = listOf("No link tapped yet"))
    }

    // ---- 9. buttons ---------------------------------------------------------------------

    private val buttons = GalleryScenario("buttons") {
        lateinit var filled: MaterialButton
        lateinit var counter: TextView
        var taps = 0
        open { a ->
            val p = Page(a, "Buttons", scrolls = true)
            val edit = { ContextCompat.getDrawable(a, com.google.android.material.R.drawable.material_ic_edit_black_24dp)!!.mutate() }
            filled = MaterialButton(a).apply { text = "Filled" }
            counter = label(a, "Tapped 0 times", 16f)
            filled.setOnClickListener {
                taps++
                counter.text = "Tapped $taps times"
            }
            p.row(filled, MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Outlined" })
            p.row(
                MaterialButton(a, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { text = "Text button" },
                MaterialButton(a).apply { text = "Disabled"; isEnabled = false },
            )
            p.row(
                MaterialButton(a, null, com.google.android.material.R.attr.materialIconButtonFilledStyle).apply {
                    icon = ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_search_api_material)
                    contentDescription = "Search"
                },
                MaterialButton(a).apply { text = "Edit with icon"; icon = edit() },
                ImageButton(a).apply { setImageResource(androidx.appcompat.R.drawable.abc_ic_menu_overflow_material); contentDescription = "More" },
            )
            p.row(Button(a).apply { text = "Plain button" }, Chip(a).apply { text = "Filter chip"; isCheckable = true; isChecked = true })
            p.row(FloatingActionButton(a).apply { setImageDrawable(edit()) }, ExtendedFloatingActionButton(a).apply { text = "Compose"; icon = edit() })
            p.add(counter, top = 8)
            p.view
        }
        checkpoint(shows = listOf("Filled", "Outlined", "Text button", "Disabled", "Edit with icon", "PLAIN BUTTON", "Filter chip", "Compose", "Tapped 0 times"))
        repeat(3) { tap(filled) }
        expect(taps == 3, "three taps reached the button: $taps")
        checkpoint("tapped", shows = listOf("Tapped 3 times"), hides = listOf("Tapped 0 times"))
    }

    // ---- 10. controls -------------------------------------------------------------------

    private val controls = GalleryScenario("controls") {
        lateinit var bluetooth: MaterialSwitch
        lateinit var news: CheckBox
        lateinit var large: RadioButton
        lateinit var month: MaterialButton
        lateinit var seek: SeekBar
        lateinit var volume: TextView
        lateinit var spinner: ProgressBar
        lateinit var dots: TabLayout
        open { a ->
            val p = Page(a, "Controls", scrolls = true)
            bluetooth = MaterialSwitch(a).apply { text = "Bluetooth" }
            p.add(MaterialSwitch(a).apply { text = "Wi-Fi"; isChecked = true }, bottom = 0)
            p.add(bluetooth, bottom = 0)
            p.add(SwitchCompat(a).apply { text = "Compat switch"; isChecked = true }, bottom = 4)
            news = CheckBox(a).apply { text = "Send news" }
            p.row(CheckBox(a).apply { text = "Remember me"; isChecked = true }, news, CheckBox(a).apply { text = "Disabled box"; isChecked = true; isEnabled = false }, spacing = 4)
            large = RadioButton(a).apply { text = "Large"; id = View.generateViewId() }
            p.add(
                RadioGroup(a).apply {
                    orientation = RadioGroup.HORIZONTAL
                    val medium = RadioButton(a).apply { text = "Medium"; id = View.generateViewId() }
                    addView(RadioButton(a).apply { text = "Small"; id = View.generateViewId() })
                    addView(medium)
                    addView(large)
                    check(medium.id)
                },
            )
            val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
            month = MaterialButton(a, null, outlined).apply { text = "Month"; id = View.generateViewId() }
            p.add(
                MaterialButtonToggleGroup(a).apply {
                    isSingleSelection = true
                    val day = MaterialButton(a, null, outlined).apply { text = "Day"; id = View.generateViewId() }
                    addView(day)
                    addView(MaterialButton(a, null, outlined).apply { text = "Week"; id = View.generateViewId() })
                    addView(month)
                    check(day.id)
                },
                w = WRAP,
            )
            volume = label(a, "Volume 30%", 15f)
            seek = SeekBar(a).apply {
                max = 100
                progress = 30
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        volume.text = "Volume $value%"
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            p.add(volume, bottom = 0)
            p.add(seek, h = 40)
            p.add(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 40 }, h = 16)
            p.add(LinearProgressIndicator(a).apply { progress = 70 })
            spinner = ProgressBar(a).apply { isIndeterminate = true }.lp(40, 40)
            p.row(CircularProgressIndicator(a).apply { progress = 60 }, spinner, RatingBar(a).apply { numStars = 5; stepSize = 0.5f; rating = 3.5f })
            dots = TabLayout(a).apply {
                tabMode = TabLayout.MODE_FIXED
                tabGravity = TabLayout.GRAVITY_CENTER
                setSelectedTabIndicatorColor(Color.TRANSPARENT)
                tabRippleColor = null
                for (i in 0 until 4) {
                    addTab(
                        newTab().setCustomView(
                            View(a).apply {
                                background = StateListDrawable().apply {
                                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6750A4.toInt()) })
                                    addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFCAC4D0.toInt()) })
                                }
                                layoutParams = ViewGroup.LayoutParams(a.dp(10), a.dp(10))
                            },
                        ),
                    )
                }
            }
            p.add(dots, w = 200, h = 32)
            p.view
        }
        val spin = listOf(frameDp(spinner))
        checkpoint(shows = listOf("Wi-Fi", "Bluetooth", "Compat switch", "Remember me", "Send news", "Disabled box", "Small", "Medium", "Large", "Day", "Week", "Month", "Volume 30%", "★"), ignore = spin)
        tap(bluetooth)
        tap(news)
        tap(large)
        tap(month)
        val (x0, y) = centerOf(seek)
        val track = seek.width - seek.paddingLeft - seek.paddingRight
        val left = onScreenPx(seek, RectF(0f, 0f, 0f, 0f)).left + seek.paddingLeft
        drag(left + track * 0.3f, y, left + track * 0.8f, y)
        tap(dots.getTabAt(2)!!.view)
        expect(bluetooth.isChecked && news.isChecked && large.isChecked && month.isChecked, "the taps reached the controls")
        expect(seek.progress in 70..90, "the drag moved the slider: ${seek.progress} (from ${x0.roundToInt()})")
        expect(dots.selectedTabPosition == 2, "the third dot is selected")
        checkpoint("changed", shows = listOf(volume.text.toString()), hides = listOf("Volume 30%"), ignore = spin)
    }

    // ---- 11. forms ----------------------------------------------------------------------

    private val forms = GalleryScenario("forms") {
        lateinit var name: EditText
        lateinit var email: TextInputEditText
        lateinit var password: TextInputEditText
        lateinit var notes: EditText
        open { a ->
            val p = Page(a, "Forms", scrolls = true)
            name = p.add(EditText(a).apply { hint = "Your name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME })
            fun field(style: Int, hint: String, type: Int, setup: TextInputLayout.() -> Unit = {}): TextInputEditText {
                val layout = TextInputLayout(a, null, style).apply { this.hint = hint; setup() }
                val edit = TextInputEditText(layout.context).apply { inputType = type }
                layout.addView(edit)
                p.add(layout)
                return edit
            }
            email = field(com.google.android.material.R.attr.textInputFilledStyle, "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            field(com.google.android.material.R.attr.textInputOutlinedStyle, "Phone", InputType.TYPE_CLASS_PHONE) { helperText = "With the country code" }
            password = field(com.google.android.material.R.attr.textInputOutlinedStyle, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            }
            field(com.google.android.material.R.attr.textInputOutlinedStyle, "City", InputType.TYPE_CLASS_TEXT) { placeholderText = "e.g. Berlin" }
            notes = p.add(
                EditText(a).apply {
                    hint = "Notes for the courier"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    gravity = Gravity.TOP or Gravity.START
                },
            )
            p.view
        }
        checkpoint(shows = listOf("Forms", "Your name", "Email", "Phone", "With the country code", "Password", "City", "Notes for the courier"))
        tap(email)
        checkpoint("focused", shows = listOf("Email"))
        for (s in listOf("Ada Lovelace", "ada@example.com", "hunter2", "Ring twice")) secret(s)
        type(name, "Ada Lovelace")
        type(email, "ada@example.com")
        type(password, "hunter2")
        type(notes, "Ring twice")
        checkpoint("filled", shows = listOf("*** ********", "•••••••", "**** *****"), hides = listOf("Your name", "Notes for the courier"))
    }

    // ---- 12. keyboard -------------------------------------------------------------------

    private val keyboard = GalleryScenario("keyboard") {
        lateinit var message: EditText
        open { a ->
            FrameLayout(a).apply {
                val p = Page(a, "Messages")
                p.add(label(a, "Write a message to the team", 16f))
                message = p.add(EditText(a).apply { hint = "Message" })
                addView(p.view, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(
                    MaterialButton(a).apply { text = "Send message" },
                    FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(a.dp(20), 0, a.dp(20), a.dp(16)) },
                )
            }
        }
        tap(message)
        expect(message.isFocused, "the tap focused the field")
        showKeyboard(300f)
        checkpoint(shows = listOf("Keyboard", "Message", "Write a message to the team", "Messages"))
        secret("See you at noon")
        type(message, "See you at noon")
        checkpoint("typed", shows = listOf("Keyboard", "*** *** ** ****"))
        hideKeyboard()
        checkpoint("hidden", shows = listOf("Send message"), hides = listOf("Keyboard"))
    }

    // ---- 13, 14. images -----------------------------------------------------------------

    private fun imagesScreen(d: GalleryDriver, collection: String) {
        lateinit var crop: ImageView
        d.policy(collection)
        d.open { a ->
            val p = Page(a, "Images", scrolls = true)
            fun cell(caption: String, scale: ImageView.ScaleType): LinearLayout = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                val image = ImageView(a).apply {
                    setImageDrawable(BitmapDrawable(a.resources, photo()))
                    scaleType = scale
                    setBackgroundColor(0xFFE0E0E0.toInt())
                }
                if (scale == ImageView.ScaleType.CENTER_CROP) crop = image
                addView(image, LinearLayout.LayoutParams(a.dp(172), a.dp(100)))
                addView(label(a, caption, 13f))
            }
            p.row(cell("Fill the box", ImageView.ScaleType.FIT_XY), cell("Fit inside", ImageView.ScaleType.FIT_CENTER), spacing = 16)
            p.row(cell("Centered as is", ImageView.ScaleType.CENTER), cell("Crop to fill", ImageView.ScaleType.CENTER_CROP), spacing = 16)
            fun icon(res: Int, sizeDp: Int, tint: Int?) = ImageView(a).apply {
                setImageDrawable(ContextCompat.getDrawable(a, res)!!.mutate().apply { if (tint != null) setTint(tint) })
            }.lp(sizeDp, sizeDp)
            val search = androidx.appcompat.R.drawable.abc_ic_search_api_material
            p.row(icon(search, 32, Color.RED), icon(search, 32, Color.BLUE), icon(search, 32, 0xFF2E7D32.toInt()), label(a, "Tinted icons", 14f))
            p.row(
                icon(com.google.android.material.R.drawable.material_ic_calendar_black_24dp, 56, null),
                icon(com.google.android.material.R.drawable.ic_clock_black_24dp, 56, 0xFF6750A4.toInt()),
                label(a, "Vector images", 14f),
            )
            val avatar = ImageView(a).apply {
                setImageDrawable(BitmapDrawable(a.resources, photo()))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.LTGRAY) }
                clipToOutline = true
            }.lp(64, 64)
            val backed = FrameLayout(a).apply {
                background = BitmapDrawable(a.resources, photo())
                addView(label(a, "Bitmap background", 14f, Color.WHITE, bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }.lp(190, 70)
            p.row(avatar, label(a, "Avatar", 14f), backed)
            p.view
        }
        d.checkpoint(shows = listOf("Fill the box", "Fit inside", "Centered as is", "Crop to fill", "Tinted icons", "Vector images", "Avatar", "Bitmap background"))
        crop.setImageDrawable(BitmapDrawable(crop.resources, photo(night = true)))
        d.checkpoint("changed", shows = listOf("Crop to fill"))
    }

    private val images = GalleryScenario("images") { imagesScreen(this, """{"replay":{"images":"always"}}""") }

    private val imagesOff = GalleryScenario("images-off") { imagesScreen(this, """{"replay":{"images":"off"}}""") }

    // ---- 15. lists ----------------------------------------------------------------------

    private val lists = GalleryScenario("lists") {
        lateinit var list: RecyclerView
        val items = ArrayList<String>()
        for (section in listOf("A", "B", "C")) {
            items.add("#Section $section")
            for (i in 1..12) items.add("Row $section${"%02d".format(i)}")
        }
        open { a ->
            list = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemViewType(position: Int) = if (items[position].startsWith("#")) 1 else 0

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val v: View = if (viewType == 1) {
                            label(a, "", 14f, 0xFF4A148C.toInt(), bold = true).apply {
                                setBackgroundColor(0xFFEDE7F6.toInt())
                                setPadding(a.dp(16), 0, a.dp(16), 0)
                                gravity = Gravity.CENTER_VERTICAL
                                layoutParams = RecyclerView.LayoutParams(MATCH, a.dp(40))
                            }
                        } else {
                            LinearLayout(a).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(a.dp(16), 0, a.dp(16), 0)
                                addView(label(a, "", 16f))
                                addView(label(a, "", 13f, 0xFF757575.toInt()))
                                layoutParams = RecyclerView.LayoutParams(MATCH, a.dp(64))
                            }
                        }
                        return object : RecyclerView.ViewHolder(v) {}
                    }

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val t = items[position]
                        val v = holder.itemView
                        if (v is TextView) {
                            v.text = t.removePrefix("#")
                        } else {
                            ((v as ViewGroup).getChildAt(0) as TextView).text = t
                            (v.getChildAt(1) as TextView).text = "Detail ${t.removePrefix("Row ")}"
                        }
                    }

                    override fun getItemCount() = items.size
                }
            }
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a, "Lists", 26f, bold = true).apply { setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(8)) })
                addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
        fun firstShown(): String {
            val lm = list.layoutManager as LinearLayoutManager
            val i = lm.findFirstCompletelyVisibleItemPosition()
            return items[i].removePrefix("#")
        }
        checkpoint(shows = listOf("Lists", "Section A", "Row A01", "Detail A01"))
        scroll(list, dyDp = 640f)
        settle()
        expect(list.canScrollVertically(-1), "the list scrolled")
        checkpoint("middle", shows = listOf(firstShown()), hides = listOf("Row A01"))
        var n = 0
        while (list.canScrollVertically(1) && n++ < 6) scroll(list, dyDp = 700f)
        expect(!list.canScrollVertically(1), "the list reached its end")
        checkpoint("bottom", shows = listOf("Row C12"), hides = listOf("Section A"))
        n = 0
        while (list.canScrollVertically(-1) && n++ < 8) scroll(list, dyDp = -700f)
        expect(!list.canScrollVertically(-1), "the list is back at its top")
        checkpoint("top", shows = listOf("Row A01"), hides = listOf("Row C12"))
    }

    // ---- 16. grids ----------------------------------------------------------------------

    private val grids = GalleryScenario("grids") {
        lateinit var grid: RecyclerView
        open { a ->
            grid = RecyclerView(a).apply {
                layoutManager = GridLayoutManager(a, 3)
                setPadding(a.dp(12), 0, a.dp(12), 0)
                clipToPadding = false
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                        object : RecyclerView.ViewHolder(
                            label(a, "", 15f, Color.WHITE, bold = true).apply {
                                gravity = Gravity.CENTER
                                layoutParams = RecyclerView.LayoutParams(MATCH, a.dp(116)).apply { setMargins(a.dp(4), a.dp(4), a.dp(4), a.dp(4)) }
                            },
                        ) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).apply {
                            text = "Tile ${"%02d".format(position + 1)}"
                            background = GradientDrawable().apply {
                                setColor(Color.HSVToColor(floatArrayOf((position * 23f) % 360f, 0.55f, 0.75f)))
                                cornerRadius = a.dp(12).toFloat()
                            }
                        }
                    }

                    override fun getItemCount() = 45
                }
            }
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(a, "Grids", 26f, bold = true).apply { setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(8)) })
                addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
        checkpoint(shows = listOf("Grids", "Tile 01", "Tile 02", "Tile 03", "Tile 12"))
        val lm = grid.layoutManager as GridLayoutManager
        scroll(grid, dyDp = 600f)
        settle()
        val first = lm.findFirstCompletelyVisibleItemPosition() + 1
        checkpoint("scrolled", shows = listOf("Tile ${"%02d".format(first)}"), hides = listOf("Tile 01"))
        scroll(grid, dyDp = 900f)
        settle()
        val further = lm.findFirstCompletelyVisibleItemPosition() + 1
        // A tile of the row above the first one on screen.
        val gone = lm.findFirstVisibleItemPosition() - 2
        expect(further > first && gone > 0, "the grid scrolled further: $first -> $further")
        checkpoint("further", shows = listOf("Tile ${"%02d".format(further)}"), hides = listOf("Tile ${"%02d".format(gone)}"))
    }

    // ---- 17. carousel -------------------------------------------------------------------

    private val carousel = GalleryScenario("carousel") {
        lateinit var strip: RecyclerView
        lateinit var page: NestedScrollView
        open { a ->
            strip = RecyclerView(a).apply {
                layoutManager = LinearLayoutManager(a, LinearLayoutManager.HORIZONTAL, false)
                setPadding(a.dp(16), 0, a.dp(16), 0)
                clipToPadding = false
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                        object : RecyclerView.ViewHolder(
                            label(a, "", 16f, Color.WHITE, bold = true).apply {
                                gravity = Gravity.BOTTOM or Gravity.START
                                setPadding(a.dp(12), 0, 0, a.dp(12))
                                layoutParams = RecyclerView.LayoutParams(a.dp(140), a.dp(130)).apply { marginEnd = a.dp(12) }
                            },
                        ) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).apply {
                            text = "Card ${"%02d".format(position + 1)}"
                            background = GradientDrawable().apply {
                                setColor(Color.HSVToColor(floatArrayOf((200f + position * 31f) % 360f, 0.6f, 0.7f)))
                                cornerRadius = a.dp(16).toFloat()
                            }
                        }
                    }

                    override fun getItemCount() = 12
                }
            }
            page = NestedScrollView(a).apply {
                addView(
                    LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(a, "Carousel", 26f, bold = true).apply { setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(4)) })
                        addView(label(a, "Featured", 16f, bold = true).apply { setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(8)) })
                        addView(strip, LinearLayout.LayoutParams(MATCH, a.dp(130)))
                        addView(label(a, "More below", 16f, bold = true).apply { setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(8)) })
                        for (i in 1..20) {
                            addView(
                                label(a, "Entry ${"%02d".format(i)}", 16f).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(a.dp(20), 0, a.dp(20), 0)
                                },
                                LinearLayout.LayoutParams(MATCH, a.dp(56)),
                            )
                        }
                    },
                )
            }
            page
        }
        checkpoint(shows = listOf("Carousel", "Featured", "Card 01", "Card 02", "More below", "Entry 01"))
        scroll(strip, dxDp = 420f)
        settle()
        val lm = strip.layoutManager as LinearLayoutManager
        expect(lm.findFirstVisibleItemPosition() >= 2, "the strip scrolled sideways")
        checkpoint("strip", shows = listOf("Card ${"%02d".format(lm.findFirstCompletelyVisibleItemPosition() + 1)}", "Entry 01"), hides = listOf("Card 01"))
        val (x, y) = centerOf(page)
        drag(x, y + 300 * density, x, y - 300 * density)
        expect(page.scrollY > activity.dp(300), "the page scrolled down: ${page.scrollY}")
        checkpoint("scrolled", shows = listOf("Entry 12"), hides = listOf("Featured"))
        drag(x, y - 300 * density, x, y + 300 * density)
        var n = 0
        while (page.scrollY > 0 && n++ < 4) drag(x, y - 300 * density, x, y + 300 * density)
        n = 0
        while (strip.canScrollHorizontally(-1) && n++ < 4) scroll(strip, dxDp = -420f)
        expect(page.scrollY == 0 && !strip.canScrollHorizontally(-1), "back at the start: ${page.scrollY}")
        checkpoint("back", shows = listOf("Card 01", "Entry 01"), hides = listOf("Card 06"))
    }

    // ---- 18. navigation -----------------------------------------------------------------

    private val navigation = GalleryScenario("navigation") {
        lateinit var first: View
        var opened = false
        var back = false
        open { a ->
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(MaterialToolbar(a).apply { title = "Inbox"; menu.add("Edit").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS) })
                for ((i, who) in listOf("Ada", "Grace", "Linus").withIndex()) {
                    val row = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12))
                        isClickable = true
                        addView(label(a, "Message from $who", 17f, bold = true))
                        addView(label(a, "Three new lines from $who", 14f, 0xFF616161.toInt()))
                        if (i == 0) setOnClickListener { opened = true }
                    }
                    if (i == 0) first = row
                    addView(row)
                }
            }
        }
        checkpoint(shows = listOf("Inbox", "EDIT", "Message from Ada", "Message from Grace", "Message from Linus"))
        tap(first)
        expect(opened, "the tap opened the message")
        lateinit var bar: MaterialToolbar
        push { a ->
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                bar = MaterialToolbar(a).apply {
                    title = "Ada"
                    navigationIcon = ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                    navigationContentDescription = "Back"
                    setNavigationOnClickListener { back = true }
                }
                addView(bar)
                addView(label(a, "Hello from the detail page", 20f, bold = true).apply { setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(8)) })
                addView(label(a, "The message body sits under the app bar, with a back arrow to return.", 16f).apply { setPadding(a.dp(20), 0, a.dp(20), 0) })
            }
        }
        checkpoint("pushed", shows = listOf("Ada", "Hello from the detail page"), hides = listOf("Message from Grace"))
        val up = (0 until bar.childCount).map { bar.getChildAt(it) }.first { it is ImageButton }
        tap(up)
        expect(back, "the back arrow was tapped")
        pop()
        checkpoint("popped", shows = listOf("Inbox", "Message from Grace"), hides = listOf("Hello from the detail page"))
    }

    // ---- 19. tabs -----------------------------------------------------------------------

    private val tabs = GalleryScenario("tabs") {
        lateinit var tabLayout: TabLayout
        lateinit var pager: ViewPager2
        val names = listOf("Home", "Search", "Profile")
        open { a ->
            tabLayout = TabLayout(a)
            pager = ViewPager2(a).apply {
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                        object : RecyclerView.ViewHolder(
                            LinearLayout(a).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(a.dp(20), a.dp(20), a.dp(20), a.dp(20))
                                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                                addView(label(a, "", 24f, bold = true))
                                addView(label(a, "", 16f))
                            },
                        ) {}

                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val v = holder.itemView as ViewGroup
                        (v.getChildAt(0) as TextView).text = "${names[position]} page"
                        (v.getChildAt(1) as TextView).text = "What the ${names[position].lowercase()} tab shows."
                        v.setBackgroundColor(listOf(0xFFFFFFFF, 0xFFF3E5F5, 0xFFE8F5E9)[position].toInt())
                    }

                    override fun getItemCount() = names.size
                }
            }
            TabLayoutMediator(tabLayout, pager) { tab, i -> tab.text = names[i] }.attach()
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(MaterialToolbar(a).apply { title = "Tabs" })
                addView(tabLayout)
                addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
        checkpoint(shows = listOf("Tabs", "Home", "Search", "Profile", "Home page"))
        tap(tabLayout.getTabAt(1)!!.view)
        settle(700)
        expect(pager.currentItem == 1, "the tab switched the page")
        checkpoint("search", shows = listOf("Search page"), hides = listOf("Home page"))
        scroll(pager, dxDp = 300f)
        settle(700)
        expect(pager.currentItem == 2, "the swipe switched the page: ${pager.currentItem}")
        checkpoint("profile", shows = listOf("Profile page"), hides = listOf("Search page"))
    }

    // ---- 20. dialogs --------------------------------------------------------------------

    private val dialogs = GalleryScenario("dialogs") {
        lateinit var delete: MaterialButton
        lateinit var share: MaterialButton
        var alert: AlertDialog? = null
        var sheet: AppCompatDialog? = null
        lateinit var cancelRow: View
        open { a ->
            val p = Page(a, "Dialogs")
            p.add(label(a, "Behind the dialog", 18f))
            p.add(label(a, "The page stays under the dialog and its dim.", 15f))
            delete = p.add(MaterialButton(a).apply { text = "Delete photo" }, w = WRAP)
            share = p.add(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Share photo" }, w = WRAP)
            delete.setOnClickListener {
                alert = MaterialAlertDialogBuilder(a).setTitle("Delete photo?").setMessage("This cannot be undone.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", null).show()
            }
            share.setOnClickListener {
                sheet = AppCompatDialog(a).apply {
                    val content = LinearLayout(a).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, a.dp(8), 0, a.dp(8))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            val r = a.dp(20).toFloat()
                            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                        }
                        for (t in listOf("Copy link", "Send to a friend", "Save to files", "Cancel action")) {
                            val row = label(a, t, 17f, if (t.startsWith("Cancel")) 0xFF6750A4.toInt() else Color.BLACK).apply {
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(a.dp(24), 0, a.dp(24), 0)
                                isClickable = true
                                setOnClickListener { dismiss() }
                            }
                            if (t.startsWith("Cancel")) cancelRow = row
                            addView(row, LinearLayout.LayoutParams(MATCH, a.dp(56)))
                        }
                    }
                    setContentView(content)
                    window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window!!.setLayout(MATCH, WRAP)
                    window!!.setGravity(Gravity.BOTTOM)
                    show()
                }
            }
            p.view
        }
        checkpoint(shows = listOf("Dialogs", "Behind the dialog", "Delete photo", "Share photo"))
        tap(delete)
        val shown = alert
        expect(shown != null && shown.isShowing, "the tap opened the alert")
        if (shown == null) return@GalleryScenario
        track(shown.window!!)
        checkpoint("alert", shows = listOf("Delete photo?", "This cannot be undone.", "Cancel", "Delete", "Behind the dialog"))
        tap(shown.getButton(AlertDialog.BUTTON_NEGATIVE))
        expect(!shown.isShowing, "Cancel closed the alert")
        settle()
        tap(share)
        val actions = sheet
        expect(actions != null && actions.isShowing, "the tap opened the actions")
        if (actions == null) return@GalleryScenario
        track(actions.window!!)
        checkpoint("action-sheet", shows = listOf("Copy link", "Send to a friend", "Save to files", "Cancel action"), hides = listOf("Delete photo?"))
        tap(cancelRow)
        expect(!actions.isShowing, "Cancel closed the actions")
        checkpoint("dismissed", shows = listOf("Behind the dialog"), hides = listOf("Copy link"))
    }

    // ---- 21. sheets ---------------------------------------------------------------------

    private val sheets = GalleryScenario("sheets") {
        lateinit var button: MaterialButton
        lateinit var heading: TextView
        var sheet: BottomSheetDialog? = null
        open { a ->
            val p = Page(a, "Sheets")
            p.add(label(a, "Under the sheet", 18f))
            for (i in 1..3) p.add(label(a, "Page line $i stays behind the sheet", 15f))
            button = p.add(MaterialButton(a).apply { text = "Open sheet" }, w = WRAP)
            button.setOnClickListener {
                sheet = BottomSheetDialog(a).apply {
                    setContentView(
                        LinearLayout(a).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(a.dp(24), a.dp(16), a.dp(24), a.dp(16))
                            heading = label(a, "A half-height sheet", 20f, bold = true)
                            addView(heading)
                            for (i in 1..9) addView(label(a, "Sheet row $i", 16f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(MATCH, a.dp(56)))
                        },
                    )
                    behavior.isFitToContents = false
                    behavior.halfExpandedRatio = 0.5f
                    behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                    show()
                }
            }
            p.view
        }
        tap(button)
        val shown = sheet
        expect(shown != null && shown.isShowing, "the tap opened the sheet")
        if (shown == null) return@GalleryScenario
        track(shown.window!!)
        checkpoint(shows = listOf("A half-height sheet", "Sheet row 1", "Under the sheet"), settle = 900)
        shown.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        heading.text = "Now a full-height sheet"
        checkpoint("large", shows = listOf("Now a full-height sheet", "Sheet row 9"), hides = listOf("A half-height sheet"), settle = 900)
        // A tap on the dim above the sheet closes it.
        shown.behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        settle(900)
        tapAt(activity.window.decorView.width / 2f, 100 * density)
        settle(900)
        expect(!shown.isShowing, "the tap outside closed the sheet")
        checkpoint("dismissed", shows = listOf("Under the sheet"), hides = listOf("Now a full-height sheet"))
    }

    // ---- 22. menus ----------------------------------------------------------------------

    private val menus = GalleryScenario("menus") {
        lateinit var anchor: MaterialButton
        lateinit var status: TextView
        open { a ->
            val p = Page(a, "Menus")
            p.add(label(a, "Pick an action for the file", 16f))
            anchor = p.add(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Options" }, w = WRAP)
            status = p.add(label(a, "Nothing chosen", 16f))
            anchor.setOnClickListener {
                PopupMenu(a, anchor).apply {
                    menu.add("Rename")
                    menu.add("Duplicate")
                    menu.add("Archive")
                    setOnMenuItemClickListener { item ->
                        status.text = "Chose ${item.title}"
                        true
                    }
                    show()
                }
            }
            p.view
        }
        tap(anchor)
        checkpoint(shows = listOf("Rename", "Duplicate", "Archive", "Options", "Pick an action for the file"))
        val item = find("Duplicate")
        expect(item != null, "the menu shows Duplicate")
        if (item == null) return@GalleryScenario
        tap(item)
        expect(status.text == "Chose Duplicate", "the tap chose Duplicate: ${status.text}")
        checkpoint("dismissed", shows = listOf("Chose Duplicate", "Options"), hides = listOf("Rename"))
    }

    // ---- 23. privacy --------------------------------------------------------------------

    private val privacy = GalleryScenario("privacy") {
        lateinit var pin: TextInputEditText
        lateinit var note: EditText
        policy("""{"replay":{"images":"always"}}""")
        for (s in listOf("4242 4242", "12,345.67", "QX-0042", "246813579", "meet at dawn")) secret(s)
        open { a ->
            val p = Page(a, "Privacy", scrolls = true)
            p.add(label(a, "Public text stays", 16f))
            p.add(
                label(a, "Card 4242 4242 4242 4242", 17f, Color.WHITE).apply {
                    setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
                    setBackgroundColor(0xFF37474F.toInt())
                    Traceway.setPrivacyMask(this, TracewayMaskMode.blank(0xFF263238.toInt()))
                },
            )
            p.add(
                LinearLayout(a).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFFE8EAF6.toInt())
                    setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
                    addView(ImageView(a).apply { setImageDrawable(BitmapDrawable(a.resources, photo())); scaleType = ImageView.ScaleType.CENTER_CROP }.lp(56, 56))
                    addView(label(a, "Balance 12,345.67", 18f, bold = true), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = a.dp(12) })
                    Traceway.setPrivacyMask(this, TracewayMaskMode.blur())
                },
            )
            p.add(label(a, "Account QX-0042", 16f).apply { tag = "traceway-mask" })
            p.add(ImageView(a).apply { setImageDrawable(BitmapDrawable(a.resources, photo())); scaleType = ImageView.ScaleType.CENTER_CROP; Traceway.setPrivacyMask(this, TracewayMaskMode.blank()) }, w = 160, h = 90)
            p.add(ImageView(a).apply { setImageDrawable(BitmapDrawable(a.resources, photo(night = true))); scaleType = ImageView.ScaleType.CENTER_CROP }, w = 160, h = 90)
            val layout = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "PIN" }
            pin = TextInputEditText(layout.context).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            layout.addView(pin)
            p.add(layout)
            note = p.add(EditText(a).apply { hint = "Private note" })
            p.view
        }
        checkpoint(shows = listOf("Public text stays", "******* *********", "******* *******", "PIN", "Private note"), hides = listOf("4242", "12,345.67", "QX-0042"))
        type(pin, "246813579")
        type(note, "meet at dawn")
        checkpoint("changed", shows = listOf("•••••••••", "**** ** ****"), hides = listOf("246813579", "meet at dawn", "Private note"))
    }

    // ---- 24. declarative ----------------------------------------------------------------

    private class ComposeState {
        val count: MutableState<Int> = mutableStateOf(0)
        val notify = mutableStateOf(false)
        val agree = mutableStateOf(true)
        val name = mutableStateOf("")
    }

    @Composable
    private fun DeclarativeScreen(state: ComposeState) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Compose screen", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Plain Compose text in a paragraph long enough to wrap onto a second line.", Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { state.count.value++ }, Modifier.testTag("add")) { Text("Add one") }
                    Text("Count ${state.count.value}", Modifier.padding(start = 16.dp))
                }
                OutlinedButton(onClick = {}, Modifier.padding(top = 4.dp)) { Text("Outlined action") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.notify.value, onCheckedChange = { state.notify.value = it }, Modifier.testTag("notify"))
                    Text("Notifications", Modifier.padding(start = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.agree.value, onCheckedChange = { state.agree.value = it })
                    Text("I agree to the terms")
                }
                TextField(value = state.name.value, onValueChange = { state.name.value = it }, label = { Text("Full name") }, modifier = Modifier.padding(top = 4.dp))
                OutlinedTextField(value = "", onValueChange = {}, label = { Text("Home city") }, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(ComposeColor(0xFF6750A4), CircleShape))
                    Box(Modifier.size(width = 72.dp, height = 44.dp).background(ComposeColor(0xFF7D5260), RoundedCornerShape(12.dp)))
                    Text("Shapes drawn by Compose")
                }
                LazyColumn(Modifier.padding(top = 10.dp).fillMaxWidth().height(200.dp).testTag("rows")) {
                    items(30) { i ->
                        Text("List row ${i + 1}", Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }

    private val declarative = GalleryScenario("declarative") {
        val state = ComposeState()
        lateinit var compose: ComposeView
        open { a ->
            compose = ComposeView(a).recomposingOnTestFrames().apply { setContent { DeclarativeScreen(state) } }
            compose
        }
        checkpoint(shows = listOf("Compose screen", "Plain Compose text", "Add one", "Count 0", "Outlined action", "Notifications", "I agree to the terms", "Full name", "Home city", "List row 1"))
        val add = composeFrame(compose) { it.config.getOrNull(SemanticsProperties.TestTag) == "add" }
        repeat(2) { tapAt(add.centerX(), add.centerY()) }
        val notify = composeFrame(compose) { it.config.getOrNull(SemanticsProperties.TestTag) == "notify" }
        tapAt(notify.centerX(), notify.centerY())
        expect(state.count.value == 2 && state.notify.value, "the taps reached the button and the switch: ${state.count.value} ${state.notify.value}")
        secret("Grace Hopper")
        state.name.value = "Grace Hopper"
        checkpoint("changed", shows = listOf("Count 2", "***** ******"), hides = listOf("Count 0"))
        val rows = composeFrame(compose) { it.config.getOrNull(SemanticsProperties.TestTag) == "rows" }
        drag(rows.centerX(), rows.bottom - 20 * density, rows.centerX(), rows.top + 20 * density)
        checkpoint("scrolled", shows = listOf("List row 8"), hides = listOf("List row 1"))
    }

    // ---- 25. native ---------------------------------------------------------------------

    private val native = GalleryScenario("native") {
        lateinit var map: View
        lateinit var chart: Sparkline
        open { a ->
            val p = Page(a, "Native", scrolls = true)
            p.add(label(a, "Web content is a placeholder", 15f), bottom = 4)
            p.add(WebView(a).apply { loadData("<h1>Hello</h1>", "text/html", "utf-8") }, h = 140)
            p.add(label(a, "Places and players", 15f), bottom = 4)
            map = p.add(GalleryMapView(a).apply { setBackgroundColor(0xFFA5D6A7.toInt()) }, h = 110)
            p.row(SurfaceView(a).lp(170, 90), VideoView(a).lp(170, 90))
            p.add(TextureView(a), h = 70, top = 4)
            p.add(label(a, "Custom chart", 15f), bottom = 4)
            chart = p.add(Sparkline(a), h = 60)
            p.view
        }
        checkpoint(shows = listOf("Web content is a placeholder", "Map", "Native view", "Video", "Custom chart"))
        map.visibility = View.GONE
        chart.values = floatArrayOf(0.9f, 0.4f, 0.7f, 0.2f, 0.8f)
        chart.invalidate()
        checkpoint("changed", shows = listOf("Custom chart", "Native view"), hides = listOf("Map"))
    }

    /** A map SDK's view, by its class name. */
    class GalleryMapView(context: Context) : FrameLayout(context)

    /** A chart that draws itself. */
    class Sparkline(context: Context) : View(context) {
        var values = floatArrayOf(0.2f, 0.6f, 0.4f, 0.9f, 0.5f)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFFE3F2FD.toInt())
            val step = width / (values.size - 1f)
            for (i in 1 until values.size) {
                canvas.drawLine((i - 1) * step, height * (1 - values[i - 1]), i * step, height * (1 - values[i]), paint)
            }
        }
    }

    // ---- 26. motion ---------------------------------------------------------------------

    private val motion = GalleryScenario("motion") {
        lateinit var mover: TextView
        open { a ->
            val p = Page(a, "Motion")
            p.add(label(a, "Watch the box move across", 16f))
            mover = label(a, "Moving box", 15f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(0xFFE65100.toInt()); cornerRadius = a.dp(12).toFloat() }
            }
            p.add(FrameLayout(a).apply { setBackgroundColor(0xFFFFF3E0.toInt()); addView(mover, FrameLayout.LayoutParams(a.dp(120), a.dp(56), Gravity.CENTER_VERTICAL)) }, h = 120)
            p.view
        }
        checkpoint("start", shows = listOf("Moving box"))
        val distance = activity.dp(240).toFloat()
        val animation = ObjectAnimator.ofFloat(mover, View.TRANSLATION_X, 0f, distance).apply {
            duration = 1500
            interpolator = LinearInterpolator()
        }
        animation.start()
        elapse(700)
        // Held still while the replay is looked at.
        animation.pause()
        expect(mover.translationX > 0f && mover.translationX < distance, "the box is on its way: ${mover.translationX}")
        checkpoint("moving", shows = listOf("Moving box"), settle = 0, minMutations = 3)
        animation.resume()
        elapse(1200)
        expect(mover.translationX == distance, "the box arrived: ${mover.translationX}")
        checkpoint("end", shows = listOf("Moving box"))
    }

    // ---- 27. rtl ------------------------------------------------------------------------

    private val rtl = GalleryScenario("rtl") {
        lateinit var root: View
        // As an app that declares android:supportsRtl.
        val info = RuntimeEnvironment.getApplication().applicationInfo
        info.flags = info.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        open { a ->
            val p = Page(a, "Right to left")
            p.row(box(a, 0xFF1E88E5.toInt(), "First"), box(a, 0xFF43A047.toInt(), "Second"), box(a, 0xFFFB8C00.toInt(), "Third"))
            p.add(label(a, "Start aligned", 16f).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setBackgroundColor(0xFFF1F8E9.toInt()) })
            p.add(label(a, "مرحبا بالعالم", 20f))
            p.add(label(a, "שלום עולם", 20f))
            p.add(label(a, "Total 42 ₪ שקל", 16f))
            p.add(CheckBox(a).apply { text = "Remember this device" })
            p.add(
                label(a, "Icon before text", 16f).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(a, androidx.appcompat.R.drawable.abc_ic_search_api_material), null, null, null)
                    compoundDrawablePadding = a.dp(8)
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            p.add(SeekBar(a).apply { max = 100; progress = 30 }, h = 40)
            root = p.view
            root.layoutDirection = View.LAYOUT_DIRECTION_RTL
            root
        }
        checkpoint(shows = listOf("Right to left", "First", "Second", "Third", "Start aligned", "مرحبا بالعالم", "שלום עולם", "Remember this device", "Icon before text"))
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        checkpoint("changed", shows = listOf("First", "Start aligned"))
    }

    // ---- 28. dark-mode ------------------------------------------------------------------

    private val darkMode = GalleryScenario("dark-mode") {
        lateinit var toggle: MaterialSwitch
        RuntimeEnvironment.setQualifiers("+night")
        val screen = { a: GalleryDayNightActivity ->
                val p = Page(a, "Dark mode")
                p.add(TextView(a).apply { text = "Colours come from the theme"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                p.add(
                    MaterialCardView(a).apply {
                        radius = a.dp(16).toFloat()
                        addView(
                            TextView(a).apply {
                                text = "Card on a dark surface"
                                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                                setPadding(a.dp(16), a.dp(20), a.dp(16), a.dp(20))
                            },
                        )
                    },
                )
                p.add(MaterialButton(a).apply { text = "Dark button" }, w = WRAP)
                toggle = p.add(MaterialSwitch(a).apply { text = "Dark switch" })
                p.add(CheckBox(a).apply { text = "Dark checkbox"; isChecked = true })
                val layout = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Dark field" }
                layout.addView(TextInputEditText(layout.context))
                p.add(layout)
                p.view
            }
        try {
            open(GalleryDayNightActivity::class.java, screen)
            checkpoint(shows = listOf("Dark mode", "Colours come from the theme", "Card on a dark surface", "Dark button", "Dark switch", "Dark checkbox", "Dark field"))
            tap(toggle)
            expect(toggle.isChecked, "the tap turned the switch on")
            RuntimeEnvironment.setQualifiers("+notnight")
            open(GalleryDayNightActivity::class.java, screen)
            checkpoint("light", shows = listOf("Dark mode", "Colours come from the theme", "Dark button"))
        } finally {
            RuntimeEnvironment.setQualifiers("+notnight")
        }
    }
}

// ---- building screens ------------------------------------------------------------------

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

/** A label in the theme's text color unless [color] says otherwise. */
internal fun label(a: Context, text: CharSequence, sp: Float = 16f, color: Int? = null, bold: Boolean = false): TextView =
    TextView(a).apply {
        this.text = text
        textSize = sp
        if (color != null) setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** LinearLayout params of [w] x [h] dp ([MATCH] and [WRAP] as they are). */
internal fun <V : View> V.lp(w: Int, h: Int): V {
    val c = context
    layoutParams = LinearLayout.LayoutParams(if (w < 0) w else c.dp(w), if (h < 0) h else c.dp(h))
    return this
}

/** A colored box of [w] x [h] dp with [text] in its middle. */
internal fun box(a: Context, color: Int, text: String, w: Int = 104, h: Int = 56): FrameLayout = FrameLayout(a).apply {
    setBackgroundColor(color)
    addView(label(a, text, 14f, Color.WHITE, bold = true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
}.lp(w, h)

/** A page of an app: a title over a column of rows, in a scroll view when [scrolls]. */
internal class Page(private val a: Context, title: String, scrolls: Boolean = false) {
    val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(16))
    }
    val view: View = if (scrolls) ScrollView(a).apply { addView(column, ViewGroup.LayoutParams(MATCH, WRAP)) } else column

    init {
        add(label(a, title, 26f, bold = true), bottom = 12)
    }

    /** Adds [v] as a row [w] x [h] dp wide and tall ([MATCH], [WRAP]), [start] dp in. */
    fun <V : View> add(v: V, w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 10, start: Int = 0): V {
        column.addView(
            v,
            LinearLayout.LayoutParams(if (w < 0) w else a.dp(w), if (h < 0) h else a.dp(h)).apply {
                topMargin = a.dp(top)
                bottomMargin = a.dp(bottom)
                marginStart = a.dp(start)
            },
        )
        return v
    }

    /** Views side by side, [spacing] dp apart, centered on one another. */
    fun row(vararg views: View, spacing: Int = 12, bottom: Int = 10): LinearLayout {
        val r = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        for ((i, v) in views.withIndex()) {
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP, WRAP)
            if (i > 0 && lp.marginStart == 0) lp.marginStart = a.dp(spacing)
            r.addView(v, lp)
        }
        add(r, bottom = bottom)
        return r
    }
}

/** A photo the app made itself: a sky, a sun (a moon at [night]) and a meadow. */
internal fun photo(night: Boolean = false): Bitmap {
    val w = 480
    val h = 300
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(
        0f, 0f, 0f, h * 0.7f,
        if (night) 0xFF0D1B3D.toInt() else 0xFF5A99EB.toInt(), if (night) 0xFF3A4D7A.toInt() else 0xFFBFE0FF.toInt(),
        Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    p.color = if (night) 0xFFF2F2E6.toInt() else 0xFFFFD640.toInt()
    c.drawCircle(w * 0.72f, h * 0.27f, h * 0.13f, p)
    p.color = if (night) 0xFF1F3D24.toInt() else 0xFF408C3D.toInt()
    c.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), p)
    b.setHasAlpha(false)
    return b
}

// ---- driving screens -------------------------------------------------------------------

/** Where [v] is on the screen, in dp. */
internal fun GalleryDriver.frameDp(v: View): RectF {
    val r = onScreenPx(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()))
    return RectF(r.left / density, r.top / density, r.right / density, r.bottom / density)
}

/** The first text view on screen showing [text], top window first. */
internal fun GalleryDriver.find(text: String): TextView? {
    val list = ArrayList<WireframeRoots.Root>()
    WireframeRoots().current(list)
    fun walk(v: View): TextView? {
        if (v.visibility != View.VISIBLE) return null
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))?.let { return it }
        return null
    }
    for (root in list.reversed()) walk(root.view)?.let { return it }
    return null
}

/** Where the first Compose node of [view] that [match]es is on the screen, in pixels. */
internal fun composeFrame(view: View, match: (SemanticsNode) -> Boolean): RectF {
    fun owner(v: View): ViewRootForTest? {
        if (v is ViewRootForTest) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) owner(v.getChildAt(i))?.let { return it }
        return null
    }
    val root = owner(view) ?: error("no composition in $view")
    val at = IntArray(2)
    view.rootView.getLocationOnScreen(at)
    fun walk(n: SemanticsNode): SemanticsNode? {
        if (match(n)) return n
        for (c in n.children) walk(c)?.let { return it }
        return null
    }
    val n = walk(root.semanticsOwner.unmergedRootSemanticsNode) ?: error("no such node")
    val p = n.positionInWindow
    return RectF(p.x + at[0], p.y + at[1], p.x + at[0] + n.size.width, p.y + at[1] + n.size.height)
}
