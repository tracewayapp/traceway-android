package com.tracewayapp.traceway.wireframe

import android.util.SparseArray
import android.view.View
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.tracewayapp.traceway.PrivacyMasks
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.compose.TracewayMaskKey
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign

/**
 * Whether Jetpack Compose is in the app, checked once. Class literals, not
 * names: R8 renames Compose's classes in a minified app, and a missing
 * class throws `NoClassDefFoundError` here, where it is caught.
 */
internal object ComposeSupport {
    val available: Boolean by lazy {
        try {
            ViewRootForTest::class.java.hashCode()
            SemanticsNode::class.java.hashCode()
            SemanticsProperties.Text.hashCode()
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Jetpack Compose content as wireframe nodes, read from the semantics tree
 * of each Compose view (the one UI tests read, no accessibility service
 * needed). Every node that shows something gets an element at its bounds:
 * text with its size, weight and color (from its text layout, read again
 * only when the text changes), text fields (masked), toggles, sliders and
 * progress drawn from their state, images and icons as placeholders,
 * buttons and selected tabs. Surface colors are not in the semantics, so
 * boxes get a neutral look over the view's own background. Images stay
 * placeholders even when replay images are on: the semantics tree holds no
 * pixels. The node kinds use the converter words of [WireframeCapture]:
 * `text`, `field`, `toggle`, `slider`, `progress`, `image`, `scroll`.
 *
 * Only this class touches Compose: it is created after [ComposeSupport]
 * found Compose in the app. Should a Compose version lack something it
 * reads, its screens become placeholders instead of failing the capture.
 */
internal class WireframeComposeCapture(private val c: WireframeCapture) {
    private class State {
        val recs = SparseArray<Rec>()
        var generation = 0
    }

    private val states = WeakHashMap<View, State>()

    /** The Compose view being captured, for its theme. */
    private var view: View? = null
    private val layouts = ArrayList<TextLayoutResult>(1)

    /** The color of the last text walked, for the button around it. */
    private var textColor = 0
    private var fontScale = 1f

    @Volatile
    private var broken = false

    fun isRoot(v: View): Boolean = v is ViewRootForTest

    fun reset() = states.clear()

    /** Adds the nodes of [view]'s composition to [out], placed from the view's top-left. */
    fun capture(view: View, viewRec: Rec, out: ArrayList<VNode>, mask: Int) {
        val root = view as? ViewRootForTest ?: return
        if (!broken) {
            var state = states[view]
            if (state == null) {
                state = State()
                states[view] = state
            }
            state.generation++
            val at = out.size
            try {
                fontScale = root.density.fontScale
                this.view = view
                walk(root.semanticsOwner.unmergedRootSemanticsNode, state, out, 0f, 0f, mask, 0)
                if (state.generation % 64 == 0) prune(state)
                return
            } catch (t: Throwable) {
                while (out.size > at) out.removeAt(out.size - 1)
                if (t is LinkageError) broken = true
            }
        }
        val sb = c.sb
        sb.setLength(0)
        sb.append("left:0;top:0;width:100%;height:100%;")
        out.add(c.element(viewRec, 8, "cp", WireframeCapture.EMPTY))
    }

    private fun rec(state: State, id: Int): Rec {
        var r = state.recs.get(id)
        if (r == null) {
            r = c.newRec()
            state.recs.put(id, r)
        }
        r.seen = state.generation
        return r
    }

    private fun prune(state: State) {
        val g = state.generation
        for (i in state.recs.size() - 1 downTo 0) {
            if (g - state.recs.valueAt(i).seen > 64) state.recs.removeAt(i)
        }
    }

    /**
     * Adds the nodes of [n] and its subtree to [out]; returns how light the
     * lightest text in it is (0 black to 1 white), or -1 without text.
     */
    private fun walk(n: SemanticsNode, state: State, out: ArrayList<VNode>, ox: Float, oy: Float, mask: Int, depth: Int): Float {
        if (depth > MAX_DEPTH) return -1f
        val bounds = n.boundsInRoot
        if (bounds.width <= 0f || bounds.height <= 0f) return -1f
        val config = n.config
        val pos = n.positionInRoot
        val size = n.size
        val x = pos.x
        val y = pos.y
        val inv = c.inv
        val wd = size.width * inv
        val hd = size.height * inv
        val rl = (x - ox) * inv
        val rt = (y - oy) * inv
        val sb = c.sb
        val rec = rec(state, n.id)

        var m = mask
        val own = config.getOrNull(TracewayMaskKey) ?: when (config.getOrNull(SemanticsProperties.TestTag)) {
            PrivacyMasks.TAG_BLUR -> TracewayMaskMode.Blur
            PrivacyMasks.TAG_BLANK -> TracewayMaskMode.blank()
            else -> null
        }
        if (own is TracewayMaskMode.Blank) {
            sb.setLength(0)
            box(sb, rl, rt, wd, hd)
            sb.append("background:").appendColor(own.color).append(';')
            out.add(c.element(rec, 0, "mk", WireframeCapture.EMPTY))
            return -1f
        }
        if (own != null) m = WireframeCapture.MASK_BLUR

        val editable = config.getOrNull(SemanticsProperties.EditableText)
        val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        val role = config.getOrNull(SemanticsProperties.Role)
        val toggle = config.getOrNull(SemanticsProperties.ToggleableState)
        val selected = config.getOrNull(SemanticsProperties.Selected)
        val text = config.getOrNull(SemanticsProperties.Text)
        val children = n.children
        val kind = when {
            editable != null -> FIELD
            range != null -> if (config.contains(SemanticsActions.SetProgress)) SLIDER else PROGRESS
            toggle != null || role == Role.Checkbox || role == Role.Switch || role == Role.RadioButton -> TOGGLE
            !text.isNullOrEmpty() -> TEXT
            role == Role.Image -> IMAGE
            children.isEmpty() && config.contains(SemanticsProperties.ContentDescription) -> IMAGE
            role == Role.Button -> BUTTON
            role == Role.Tab && selected == true -> TAB
            config.contains(SemanticsProperties.VerticalScrollAxisRange) ||
                config.contains(SemanticsProperties.HorizontalScrollAxisRange) -> SCROLL
            else -> NONE
        }
        if (kind == NONE) {
            // Nothing of its own to show: its children go into the parent's element.
            var light = -1f
            for (child in children) light = max(light, walk(child, state, out, ox, oy, m, depth + 1))
            return light
        }
        val disabled = config.contains(SemanticsProperties.Disabled)
        val kids = c.lists.acquire()
        try {
            var cls: String? = null
            val look = if (kind == FIELD) fieldLook(n, children) else FIELD_OUTLINED
            when (kind) {
                FIELD -> field(config, rec, kids, editable!!.text, wd, hd, m, look)
                TOGGLE -> toggle(rec, kids, role, toggle, selected, wd, hd, disabled)
                SLIDER -> slider(rec, kids, range!!, wd, hd)
                PROGRESS -> progress(rec, kids, range!!, wd, hd)
                else -> {}
            }
            var light = -1f
            textColor = 0
            for (child in children) light = max(light, walk(child, state, kids, x, y, m, depth + 1))
            val childTextColor = textColor
            sb.setLength(0)
            box(sb, rl, rt, wd, hd)
            when (kind) {
                TEXT -> {
                    val joined = join(text!!)
                    textLook(config, rec, joined, size.width, size.height)
                    sb.append(rec.lookCss)
                    cls = rec.lookCls
                    light = max(light, lightness(rec.lookColor))
                    textColor = rec.lookColor
                    val shown: CharSequence = if (m == WireframeCapture.MASK_BLUR) {
                        c.tb.setLength(0)
                        maskText(joined, c.tb)
                    } else {
                        joined
                    }
                    kids.add(0, c.textNode(rec, 1, shown))
                }
                FIELD -> {
                    val focused = config.getOrNull(SemanticsProperties.Focused) == true
                    when (look) {
                        FIELD_FILLED -> {
                            // Material 3's TextField: a tinted container, rounded at the top, over an indicator line.
                            sb.append("background-color:").appendColor(FILLED_CONTAINER).append(";border-radius:4px 4px 0 0;")
                                .append("box-shadow:inset 0 ").append(if (focused) "-2px" else "-1px").append(" 0 0 ")
                                .appendColor(if (focused) DEFAULT_PRIMARY else FILLED_INDICATOR).append(';')
                        }
                        FIELD_OUTLINED_LABELED -> {
                            // The outline starts below the room the field keeps for its label to sit on it.
                            val box = sb.toString()
                            sb.setLength(0)
                            sb.append("left:0;top:").appendPx(OUTLINED_LABEL_ROOM_DP).append(";width:").appendPx(wd)
                                .append(";height:").appendPx(max(0f, hd - OUTLINED_LABEL_ROOM_DP)).append(';')
                            outline(sb, focused)
                            kids.add(0, c.element(rec, 4, null, WireframeCapture.EMPTY))
                            sb.setLength(0)
                            sb.append(box)
                        }
                        else -> outline(sb, focused)
                    }
                }
                IMAGE -> {
                    if (m != WireframeCapture.MASK_BLUR && wd <= 64f && hd <= 64f) {
                        sb.append("color:#757575;")
                        cls = "ic"
                    } else {
                        cls = "im"
                    }
                }
                BUTTON -> {
                    if (wd > hd * 1.4f || wd > 64f) {
                        // Compose keeps a button's color to itself; light text means a filled button
                        // (onPrimary on primary), dark text an outlined or tonal one.
                        when {
                            light > LIGHT_TEXT ->
                                sb.append("background-color:").appendColor(view?.let { c.primary(it) } ?: DEFAULT_PRIMARY).append(';')
                            // Text in the primary color: an outlined (or text) button, which has no fill.
                            colorful(childTextColor) -> sb.append("box-shadow:inset 0 0 0 1px ").appendColor(DEFAULT_OUTLINE).append(';')
                            else -> sb.append("background-color:rgba(127,127,127,.12);box-shadow:inset 0 0 0 1px rgba(127,127,127,.28);")
                        }
                        sb.appendRadius(if (hd <= 56f) hd / 2 else 12f)
                    }
                }
                TAB -> sb.append("box-shadow:inset 0 -3px 0 0 ").appendColor(DEFAULT_PRIMARY).append(';')
                SCROLL -> sb.append("overflow:hidden;")
            }
            if (disabled && kind != TOGGLE && kind != TEXT) sb.append("opacity:0.38;")
            out.add(c.element(rec, 0, cls, kids))
            return light
        } finally {
            c.lists.release()
        }
    }

    /**
     * How a text field looks: filled (Material 3's `TextField`), outlined
     * with room for its label above the outline, or outlined. Semantics hold
     * no colors; the two place their label differently: a floating label
     * sits on an outlined field's top edge but inside a filled one, and a
     * resting label is centered under an outlined field's label room but in
     * the middle of a filled one. A field without a text inside it (no label,
     * no placeholder) cannot be told apart and keeps the outline.
     */
    private fun fieldLook(n: SemanticsNode, children: List<SemanticsNode>): Int {
        var label: SemanticsNode? = null
        for (c in children) {
            label = firstText(c, 0)
            if (label != null) break
        }
        if (label == null) return FIELD_OUTLINED
        val d = c.density
        val top = n.positionInRoot.y
        val labelTop = label.positionInRoot.y
        val labelH = label.size.height.toFloat()
        val floating = labelH <= FLOATING_LABEL_MAX_DP * d
        val outlined = if (floating) {
            labelTop - top < OUTLINED_LABEL_ROOM_DP / 2 * d
        } else {
            (labelTop + labelH / 2) - (top + n.size.height / 2f) > OUTLINED_LABEL_ROOM_DP / 4 * d
        }
        return if (outlined) FIELD_OUTLINED_LABELED else FIELD_FILLED
    }

    private fun firstText(n: SemanticsNode, depth: Int): SemanticsNode? {
        if (!n.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) return n
        if (depth >= 4) return null
        for (c in n.children) firstText(c, depth + 1)?.let { return it }
        return null
    }

    private fun outline(sb: StringBuilder, focused: Boolean) {
        sb.append("box-shadow:inset 0 0 0 ").append(if (focused) "2px " else "1px ")
            .appendColor(if (focused) DEFAULT_PRIMARY else 0x99808080.toInt()).append(";border-radius:4px;")
    }

    /**
     * A text field's own text, masked, and dots for a password: in the
     * middle of its box, below the label of a filled field, or of the
     * outline under an outlined field's label room.
     */
    private fun field(config: SemanticsConfiguration, rec: Rec, out: ArrayList<VNode>, value: String, wd: Float, hd: Float, m: Int, look: Int) {
        if (value.isEmpty()) return
        val tb = c.tb
        tb.setLength(0)
        val shown: CharSequence = when {
            config.contains(SemanticsProperties.Password) -> {
                for (i in value.indices) tb.append('•')
                tb
            }
            c.maskAllInputs || m == WireframeCapture.MASK_BLUR -> maskText(value, tb)
            else -> value
        }
        val t = c.textNode(rec, 3, shown)
        textLook(config, rec, value, 0, 0)
        val line = c.look.lineHeight.takeIf { it > 0f } ?: (c.look.fontSize * 1.17f)
        val lines = max(1, c.look.lines)
        val textH = min(hd, line * lines)
        // A decorated field (a Material TextField) has room around its text.
        val pad = if (hd > textH + 20f) 16f else 0f
        val room = if (look == FIELD_OUTLINED_LABELED) OUTLINED_LABEL_ROOM_DP else 0f
        val top = when {
            look == FIELD_FILLED && lines == 1 && pad > 0f -> hd - FILLED_BOTTOM_PADDING_DP - textH
            lines == 1 -> room + (hd - room - textH) / 2
            else -> room + pad / 2
        }
        val sb = c.sb
        sb.setLength(0)
        sb.append("left:").appendPx(pad).append(";top:").appendPx(top)
            .append(";width:").appendPx(max(0f, wd - 2 * pad)).append(";height:").appendPx(textH).append(';')
        sb.append(rec.lookCss)
        out.add(c.element(rec, 2, rec.lookCls, c.one(t)))
    }

    private fun toggle(rec: Rec, out: ArrayList<VNode>, role: Role?, state: ToggleableState?, selected: Boolean?, wd: Float, hd: Float, disabled: Boolean) {
        val on = state == ToggleableState.On || state == ToggleableState.Indeterminate || (state == null && selected == true)
        var active = DEFAULT_PRIMARY
        var inactive = DEFAULT_OUTLINE
        if (disabled) {
            active = withAlpha(active, 0.38f)
            inactive = withAlpha(inactive, 0.38f)
        }
        val wide = wd > hd * 2f
        val sb = c.sb
        if (role == Role.Switch) {
            val tw = 52f
            val th = 32f
            val cx = if (wide) wd - 16f - tw / 2 else wd / 2
            sb.setLength(0)
            WidgetCss.switchThumb(sb, tw, th, if (on) 24f else 16f, on, if (on) 0xFFFFFFFF.toInt() else inactive, compact = false)
            val thumb = c.element(rec, 6, null, WireframeCapture.EMPTY)
            sb.setLength(0)
            WidgetCss.switchTrack(sb, cx, hd / 2, tw, th, on, active, inactive, compact = false)
            out.add(c.element(rec, 5, null, c.one(thumb)))
            return
        }
        val cx = if (wide) min(24f, hd / 2) else wd / 2
        val cy = hd / 2
        val kids = c.lists.acquire()
        try {
            sb.setLength(0)
            if (role == Role.RadioButton) {
                val color = if (on) active else inactive
                if (on) {
                    WidgetCss.radioDot(sb, color)
                    kids.add(c.element(rec, 6, null, WireframeCapture.EMPTY))
                    sb.setLength(0)
                }
                WidgetCss.radio(sb, cx, cy, color)
            } else {
                if (on) {
                    WidgetCss.checkMark(sb)
                    kids.add(c.element(rec, 6, null, WireframeCapture.EMPTY))
                    sb.setLength(0)
                }
                WidgetCss.checkbox(sb, cx, cy, on, active, inactive)
            }
            out.add(c.element(rec, 5, null, kids))
        } finally {
            c.lists.release()
        }
    }

    private fun fraction(info: ProgressBarRangeInfo): Float {
        val r = info.range
        val span = r.endInclusive - r.start
        return if (span > 0f) ((info.current - r.start) / span).coerceIn(0f, 1f) else 0f
    }

    /** A slider: its track, the part up to the value, and the thumb. */
    private fun slider(rec: Rec, out: ArrayList<VNode>, info: ProgressBarRangeInfo, wd: Float, hd: Float) {
        val fraction = fraction(info)
        val color = DEFAULT_PRIMARY
        val sb = c.sb
        val pad = min(10f, hd / 2)
        val width = max(0f, wd - 2 * pad)
        val cy = hd / 2
        sb.setLength(0)
        WidgetCss.sliderTrack(sb, pad, width, cy, withAlpha(color, 0.3f))
        out.add(c.element(rec, 5, null, WireframeCapture.EMPTY))
        sb.setLength(0)
        WidgetCss.sliderTrack(sb, pad, width * fraction, cy, color)
        out.add(c.element(rec, 6, null, WireframeCapture.EMPTY))
        sb.setLength(0)
        WidgetCss.sliderThumb(sb, pad + width * fraction, cy, color)
        out.add(c.element(rec, 7, null, WireframeCapture.EMPTY))
    }

    /** A progress indicator: a bar when it is wide, a ring otherwise. */
    private fun progress(rec: Rec, out: ArrayList<VNode>, info: ProgressBarRangeInfo, wd: Float, hd: Float) {
        val value = if (info == ProgressBarRangeInfo.Indeterminate) null else fraction(info)
        val color = DEFAULT_PRIMARY
        val sb = c.sb
        if (wd > hd * 2.5f) {
            sb.setLength(0)
            WidgetCss.linearBar(sb, value, color)
            val bar = c.element(rec, 6, null, WireframeCapture.EMPTY)
            sb.setLength(0)
            sb.append("left:0;top:0;width:100%;height:100%;")
            WidgetCss.linearTrack(sb, hd, withAlpha(color, 0.24f))
            out.add(c.element(rec, 5, null, c.one(bar)))
        } else {
            sb.setLength(0)
            WidgetCss.circular(sb, wd, hd, value, color, null)
            out.add(c.element(rec, 5, null, WireframeCapture.EMPTY))
        }
    }

    /**
     * Reads how the text of [config] looks into [rec] (`lookCss`, `lookCls`),
     * from its text layout; only when the text or the box changed, since
     * Compose lays the text out again to answer.
     */
    private fun textLook(config: SemanticsConfiguration, rec: Rec, text: String, w: Int, h: Int) {
        if (rec.lookText == text && rec.lookW == w && rec.lookH == h && rec.lookCss != null) {
            return
        }
        val l = c.look.reset()
        l.color = 0xFF757575.toInt()
        var read = false
        try {
            layouts.clear()
            config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val r = layouts.firstOrNull()
            if (r != null) {
                read(r, l, w, h)
                read = true
            }
        } catch (_: Throwable) {
        } finally {
            layouts.clear()
        }
        if (!read && h > 0) {
            l.lineHeight = h * c.inv
            l.fontSize = min(14f, l.lineHeight / 1.17f)
        }
        val out = StringBuilder(96)
        rec.lookCls = out.appendTextCss(l)
        rec.lookCss = out.toString()
        rec.lookColor = l.color
        rec.lookText = text
        rec.lookW = w
        rec.lookH = h
    }

    /** How [r] looks, in a box of [w] x [h] pixels (0 when the box does not cut it). */
    private fun read(r: TextLayoutResult, l: TextLook, w: Int, h: Int) {
        val input = r.layoutInput
        val style = input.style
        val fs = style.fontSize
        l.fontSize = when {
            fs.isSp -> fs.value * fontScale
            fs.isEm -> fs.value * 14f
            else -> 14f
        }
        val lines = max(1, r.lineCount)
        l.lines = lines
        // The paragraph's own height, not the layout's: a Text given a taller box (a list row's
        // fixed height) draws its lines at the top of it.
        l.lineHeight = r.multiParagraph.height * c.inv / lines
        val color = style.color
        if (color.isSpecified) l.color = color.toArgb()
        l.weight = style.fontWeight?.weight ?: 400
        l.italic = style.fontStyle == FontStyle.Italic
        val decoration = style.textDecoration
        if (decoration != null) {
            l.underline = decoration.contains(TextDecoration.Underline)
            l.strike = decoration.contains(TextDecoration.LineThrough)
        }
        val ls = style.letterSpacing
        l.letterSpacing = when {
            ls.isEm -> ls.value
            ls.isSp && l.fontSize > 0f -> ls.value * fontScale / l.fontSize
            else -> 0f
        }
        l.monospace = style.fontFamily == FontFamily.Monospace
        val rtl = input.text.isNotEmpty() && r.getParagraphDirection(0) == ResolvedTextDirection.Rtl
        l.rtl = rtl
        l.align = when (style.textAlign) {
            ComposeTextAlign.Center -> TextAlign.CENTER
            ComposeTextAlign.Right -> TextAlign.RIGHT
            ComposeTextAlign.Left -> TextAlign.LEFT
            ComposeTextAlign.Justify -> TextAlign.JUSTIFY
            ComposeTextAlign.End -> if (rtl) TextAlign.LEFT else TextAlign.RIGHT
            else -> if (rtl) TextAlign.RIGHT else TextAlign.LEFT
        }
        // Whether the app cut the text, from its lines and its box: the result the semantics
        // action hands out is built again from the cached paragraph, and its overflow flags
        // say every text overflows.
        val last = r.lineCount - 1
        val ellipsized = last >= 0 && input.overflow == TextOverflow.Ellipsis && r.isLineEllipsized(last)
        var widest = 0f
        for (i in 0..min(last, 50)) widest = max(widest, r.getLineRight(i) - r.getLineLeft(i))
        val mp = r.multiParagraph
        l.clipped = input.overflow != TextOverflow.Visible &&
            (ellipsized || mp.didExceedMaxLines || (h > 0 && mp.height > h + 1) || (w > 0 && widest > w + 1))
        l.ellipsis = ellipsized
    }

    private companion object {
        const val MAX_DEPTH = 200

        /** The room (dp) an `OutlinedTextField` with a label keeps above its outline (`OutlinedTextFieldTopPadding`). */
        const val OUTLINED_LABEL_ROOM_DP = 8f

        /** A label this tall (dp) or less is a floating one (12 sp), not one resting in the field (16 sp). */
        const val FLOATING_LABEL_MAX_DP = 19f

        /** Below a filled field's text, as `TextFieldWithLabelVerticalPadding`. */
        const val FILLED_BOTTOM_PADDING_DP = 8f

        const val FIELD_OUTLINED = 0
        const val FIELD_OUTLINED_LABELED = 1
        const val FIELD_FILLED = 2

        /** A text color far from gray: a theme's primary rather than its on-surface text. */
        fun colorful(argb: Int): Boolean {
            if ((argb ushr 24) < 128) return false
            val r = (argb shr 16) and 0xff
            val g = (argb shr 8) and 0xff
            val b = argb and 0xff
            return max(r, max(g, b)) - min(r, min(g, b)) > 60
        }

        /** Material 3's filled text field (light scheme): surfaceContainerHighest and onSurfaceVariant. */
        const val FILLED_CONTAINER = 0xFFE6E0E9.toInt()
        const val FILLED_INDICATOR = 0xFF49454F.toInt()

        /** Text lighter than this (0-1) is light-on-dark. */
        const val LIGHT_TEXT = 0.75f

        fun lightness(argb: Int): Float =
            (0.2126f * ((argb shr 16) and 0xff) + 0.7152f * ((argb shr 8) and 0xff) + 0.0722f * (argb and 0xff)) / 255f

        const val NONE = 0
        const val TEXT = 1
        const val FIELD = 2
        const val TOGGLE = 3
        const val SLIDER = 4
        const val PROGRESS = 5
        const val IMAGE = 6
        const val BUTTON = 7
        const val TAB = 8
        const val SCROLL = 9

        fun box(sb: StringBuilder, rl: Float, rt: Float, wd: Float, hd: Float) {
            sb.append("left:").appendPx(rl).append(";top:").appendPx(rt)
                .append(";width:").appendPx(wd).append(";height:").appendPx(hd).append(';')
        }

        fun join(texts: List<*>): String {
            if (texts.size == 1) return texts[0].toString()
            val out = StringBuilder()
            for (t in texts) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(t.toString())
            }
            return out.toString()
        }
    }
}
