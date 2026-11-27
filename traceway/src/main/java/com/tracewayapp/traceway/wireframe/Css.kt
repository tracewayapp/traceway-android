package com.tracewayapp.traceway.wireframe

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Shared rules of every wireframe, the same as the Flutter SDK's, so the
 * dashboard draws Android and Flutter recordings alike. Each node carries
 * its geometry and colors inline; these set the defaults and the
 * placeholder looks: `t` text (`n` one line, `e` ellipsized, `k` clipped),
 * `im` image, `ic` icon, `cp` custom drawing, `mk` masked, `pv` native view.
 */
internal const val wireframeCss = """html,body{margin:0;padding:0;overflow:hidden;background:#000}
body{font-family:Roboto,"Segoe UI","Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased;-webkit-text-size-adjust:none;text-size-adjust:none}
#tw{position:relative;overflow:hidden}
#tw div,#tw img{position:absolute;left:0;top:0;box-sizing:border-box;margin:0}
.t{white-space:pre-wrap;overflow-wrap:break-word;line-height:1.17}
.n{white-space:pre;display:flex}
.e{display:block;overflow:hidden;text-overflow:ellipsis}
.k{display:block;overflow:hidden}
.im{background-color:rgba(158,158,158,.22);background-image:linear-gradient(to top right,transparent calc(50% - .5px),rgba(128,128,128,.45) 50%,transparent calc(50% + .5px)),linear-gradient(to bottom right,transparent calc(50% - .5px),rgba(128,128,128,.45) 50%,transparent calc(50% + .5px))}
.ic::before{content:"";position:absolute;inset:14%;border-radius:30%;background:currentColor;opacity:.62}
.cp{border:1px dashed rgba(128,128,128,.5)}
.mk{background-image:repeating-linear-gradient(45deg,rgba(255,255,255,.08) 0 6px,transparent 6px 12px)}
.pv{display:flex;align-items:center;justify-content:center;overflow:hidden;background-color:#eceff1;background-image:repeating-linear-gradient(135deg,rgba(96,125,139,.16) 0 10px,transparent 10px 20px);box-shadow:inset 0 0 0 1px rgba(96,125,139,.45);color:#455a64;font-size:13px;font-weight:500;text-align:center}
"""

// ---- numbers ----------------------------------------------------------------
//
// Lengths are rounded to a tenth of a dp, so sub-pixel animation noise does
// not turn into a mutation on every frame. The appenders write into the
// capture's one StringBuilder, so an unchanged view allocates nothing.

private const val LIMIT = 1e8f

internal fun StringBuilder.appendNum1(value: Float): StringBuilder {
    if (value.isNaN() || value.isInfinite()) return append('0')
    val r = Math.round(value.coerceIn(-LIMIT, LIMIT) * 10f)
    if (r < 0) append('-')
    val a = abs(r)
    append(a / 10)
    val f = a % 10
    if (f != 0) append('.').append(f)
    return this
}

internal fun StringBuilder.appendNum3(value: Float): StringBuilder {
    if (value.isNaN() || value.isInfinite()) return append('0')
    val r = Math.round(value.coerceIn(-LIMIT, LIMIT).toDouble() * 1000.0)
    if (r < 0) append('-')
    val a = abs(r)
    append(a / 1000)
    var f = (a % 1000).toInt()
    if (f != 0) {
        append('.')
        var digits = 3
        while (f % 10 == 0) {
            f /= 10
            digits--
        }
        val s = f.toString()
        for (i in s.length until digits) append('0')
        append(s)
    }
    return this
}

/** A length in CSS pixels (dp). */
internal fun StringBuilder.appendPx(value: Float): StringBuilder = appendNum1(value).append("px")

internal fun num1(value: Float): String = StringBuilder(8).appendNum1(value).toString()
internal fun num3(value: Float): String = StringBuilder(8).appendNum3(value).toString()
internal fun px(value: Float): String = StringBuilder(10).appendPx(value).toString()

// ---- colors -----------------------------------------------------------------

private val HEX = "0123456789abcdef".toCharArray()

/** [argb] as `#rrggbb`, or `rgba(...)` when it is not opaque. */
internal fun StringBuilder.appendColor(argb: Int): StringBuilder {
    val a = argb ushr 24
    val r = (argb shr 16) and 0xff
    val g = (argb shr 8) and 0xff
    val b = argb and 0xff
    if (a == 0xff) {
        append('#')
        append(HEX[r shr 4]).append(HEX[r and 15])
        append(HEX[g shr 4]).append(HEX[g and 15])
        append(HEX[b shr 4]).append(HEX[b and 15])
        return this
    }
    append("rgba(").append(r).append(',').append(g).append(',').append(b).append(',')
    return appendNum3(a / 255f).append(')')
}

internal fun cssColor(argb: Int): String = StringBuilder(24).appendColor(argb).toString()

internal fun isTransparent(argb: Int): Boolean = (argb ushr 24) == 0

/** [argb] with its alpha multiplied by [factor]. */
internal fun withAlpha(argb: Int, factor: Float): Int {
    val a = ((argb ushr 24) * factor.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (argb and 0xffffff)
}

internal fun StringBuilder.appendFill(argb: Int): StringBuilder {
    if (!isTransparent(argb)) append("background-color:").appendColor(argb).append(';')
    return this
}

// ---- shapes -----------------------------------------------------------------

/** `border-radius` of one radius for every corner, or nothing when square. */
internal fun StringBuilder.appendRadius(radius: Float): StringBuilder {
    if (radius > 0f) append("border-radius:").appendPx(radius).append(';')
    return this
}

/**
 * `border-radius` from Android's eight corner radii (x and y of top-left,
 * top-right, bottom-right, bottom-left), or nothing when all are square.
 */
internal fun StringBuilder.appendRadii(r: FloatArray): StringBuilder {
    if (r.size < 8) return this
    fun zero(i: Int) = r[i] <= 0f || r[i + 1] <= 0f
    if (zero(0) && zero(2) && zero(4) && zero(6)) return this
    val uniform = r[0] == r[2] && r[2] == r[4] && r[4] == r[6] &&
        r[1] == r[3] && r[3] == r[5] && r[5] == r[7]
    append("border-radius:")
    if (uniform) {
        appendPx(r[0])
        if (r[0] != r[1]) append('/').appendPx(r[1])
        return append(';')
    }
    appendPx(r[0]).append(' ').appendPx(r[2]).append(' ').appendPx(r[4]).append(' ').appendPx(r[6])
    if (r[0] != r[1] || r[2] != r[3] || r[4] != r[5] || r[6] != r[7]) {
        append('/').appendPx(r[1]).append(' ').appendPx(r[3]).append(' ').appendPx(r[5]).append(' ').appendPx(r[7])
    }
    return append(';')
}

/**
 * `box-shadow` for a surface lifted by [elevation] dp and an outline of
 * [strokeWidth] dp. Outlines are inset shadows: like Android's strokes they
 * paint inside the box, and unlike CSS borders they do not move children.
 */
internal fun StringBuilder.appendShadows(
    elevation: Float,
    shadowColor: Int,
    strokeWidth: Float,
    strokeColor: Int,
): StringBuilder {
    val lifted = elevation > 0f && !isTransparent(shadowColor)
    val outlined = strokeWidth > 0f && !isTransparent(strokeColor)
    if (!lifted && !outlined) return this
    append("box-shadow:")
    if (lifted) {
        append("0 ").appendPx(elevation * 0.5f).append(' ').appendPx(elevation * 1.6f).append(" 0 ")
        appendColor(withAlpha(shadowColor, 0.28f))
    }
    if (outlined) {
        if (lifted) append(',')
        append("inset 0 0 0 ").appendPx(strokeWidth).append(' ').appendColor(strokeColor)
    }
    return append(';')
}

private fun StringBuilder.appendStops(colors: IntArray, stops: FloatArray?): StringBuilder {
    for (i in colors.indices) {
        if (i > 0) append(',')
        appendColor(colors[i])
        if (stops != null && i < stops.size) append(' ').appendNum1(stops[i] * 100f).append('%')
    }
    return this
}

/**
 * A linear gradient from ([x0], [y0]) to ([x1], [y1]), fractions of a box of
 * [width] x [height].
 */
internal fun StringBuilder.appendLinearGradient(
    x0: Float, y0: Float, x1: Float, y1: Float,
    width: Float, height: Float,
    colors: IntArray, stops: FloatArray? = null,
): StringBuilder {
    // CSS angles run clockwise from "to top".
    val dx = (x1 - x0) * width
    val dy = (y1 - y0) * height
    var degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat() % 360f
    if (degrees < 0f) degrees += 360f
    append("background-image:linear-gradient(").appendNum1(degrees).append("deg,")
    return appendStops(colors, stops).append(");")
}

/** A radial gradient around ([cx], [cy]), fractions of the box. */
internal fun StringBuilder.appendRadialGradient(cx: Float, cy: Float, colors: IntArray): StringBuilder {
    append("background-image:radial-gradient(circle at ").appendNum1(cx * 100f).append("% ")
        .appendNum1(cy * 100f).append("%,")
    return appendStops(colors, null).append(");")
}

/** A sweep gradient around ([cx], [cy]), fractions of the box, starting at 3 o'clock. */
internal fun StringBuilder.appendSweepGradient(cx: Float, cy: Float, colors: IntArray): StringBuilder {
    append("background-image:conic-gradient(from 90deg at ").appendNum1(cx * 100f).append("% ")
        .appendNum1(cy * 100f).append("%,")
    return appendStops(colors, null).append(");")
}

// ---- text -------------------------------------------------------------------

/** Horizontal alignment of a text box. */
internal enum class TextAlign(val css: String) { LEFT("left"), CENTER("center"), RIGHT("right"), JUSTIFY("justify") }

/** What a box of text looks like, read from a TextView or a Compose text layout. */
internal class TextLook {
    /** Font size and line height in dp. */
    var fontSize = 14f
    var lineHeight = 0f

    /** Laid-out lines; one-line text that is not cut spills out of its box. */
    var lines = 1
    var color = 0xDD000000.toInt()
    var weight = 400
    var italic = false
    var underline = false
    var strike = false

    /** In em. */
    var letterSpacing = 0f
    var monospace = false
    var align = TextAlign.LEFT
    var rtl = false

    /** The app cut the text (ellipsized, more lines than shown, or wider than its box). */
    var clipped = false
    var ellipsis = false

    fun reset(): TextLook {
        fontSize = 14f; lineHeight = 0f; lines = 1; color = 0xDD000000.toInt(); weight = 400
        italic = false; underline = false; strike = false; letterSpacing = 0f; monospace = false
        align = TextAlign.LEFT; rtl = false; clipped = false; ellipsis = false
        return this
    }
}

internal fun StringBuilder.appendFont(
    color: Int, weight: Int, italic: Boolean, underline: Boolean, strike: Boolean,
    letterSpacing: Float, monospace: Boolean,
): StringBuilder {
    append("color:").appendColor(color).append(';')
    if (weight != 400 && weight > 0) append("font-weight:").append(weight).append(';')
    if (italic) append("font-style:italic;")
    if (underline || strike) {
        append("text-decoration:")
        if (underline) append("underline")
        if (underline && strike) append(' ')
        if (strike) append("line-through")
        append(';')
    }
    if (letterSpacing != 0f) append("letter-spacing:").appendNum3(letterSpacing).append("em;")
    if (monospace) append("font-family:monospace;")
    return this
}

/**
 * The inline style of a box of text, and returns its class. Lines are as
 * tall in the browser as in the app, so the text sits where it did even
 * though the browser's font is not the app's. A single line spills out of
 * its box the way it is aligned, and only text the app itself cut off is
 * clipped.
 */
internal fun StringBuilder.appendTextCss(look: TextLook): String {
    append("font-size:").appendPx(look.fontSize).append(';')
    val line = if (look.lineHeight > 0f) look.lineHeight else look.fontSize * 1.17f
    append("line-height:").appendPx(line).append(';')
    appendFont(look.color, look.weight, look.italic, look.underline, look.strike, look.letterSpacing, look.monospace)
    if (look.rtl) append("direction:rtl;")
    val align = look.align
    if (look.lines <= 1 && !look.clipped) {
        if (align != TextAlign.LEFT && align != TextAlign.JUSTIFY) append("justify-content:").append(align.css).append(';')
        return "t n"
    }
    if (align != TextAlign.LEFT) append("text-align:").append(align.css).append(';')
    if (!look.clipped) return "t"
    return if (look.lines <= 1) {
        if (look.ellipsis) "t n e" else "t n k"
    } else {
        if (look.ellipsis) "t e" else "t k"
    }
}

/** Text with every character but white space replaced by `*`. */
internal fun maskText(text: CharSequence, out: StringBuilder): StringBuilder {
    for (i in 0 until text.length) {
        val c = text[i]
        out.append(if (Character.isWhitespace(c)) c else '*')
    }
    return out
}

// ---- widgets drawn from their state -------------------------------------------
//
// Toggles, progress indicators and sliders are drawn from what they show
// (checked, a value) instead of from their pictures, like the Flutter SDK
// draws them: each appender writes the style of one part, in the box of the
// control (dp, from its top-left).

internal const val DEFAULT_PRIMARY = 0xFF6750A4.toInt()
internal const val DEFAULT_OUTLINE = 0xFF79747E.toInt()

internal object WidgetCss {
    const val CHECK_SIDE = 18f
    const val RADIO_SIDE = 20f

    fun checkbox(sb: StringBuilder, cx: Float, cy: Float, checked: Boolean, active: Int, inactive: Int) {
        val s = CHECK_SIDE
        sb.append("left:").appendPx(cx - s / 2).append(";top:").appendPx(cy - s / 2)
            .append(";width:").appendPx(s).append(";height:").appendPx(s).append(";border-radius:2px;")
        if (checked) {
            sb.append("background-color:").appendColor(active).append(';')
        } else {
            sb.append("box-shadow:inset 0 0 0 2px ").appendColor(inactive).append(';')
        }
    }

    fun checkMark(sb: StringBuilder) {
        sb.append("left:6px;top:2px;width:6px;height:11px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg);")
    }

    fun radio(sb: StringBuilder, cx: Float, cy: Float, color: Int) {
        val s = RADIO_SIDE
        sb.append("left:").appendPx(cx - s / 2).append(";top:").appendPx(cy - s / 2)
            .append(";width:").appendPx(s).append(";height:").appendPx(s)
            .append(";border-radius:50%;box-shadow:inset 0 0 0 2px ").appendColor(color).append(';')
    }

    fun radioDot(sb: StringBuilder, color: Int) {
        sb.append("left:5px;top:5px;width:10px;height:10px;border-radius:50%;background-color:")
            .appendColor(color).append(';')
    }

    /**
     * A switch's track of [w] x [h] centered on ([cx], [cy]). Material 3
     * tracks are filled when on and outlined when off; compact (Material 2)
     * tracks are filled either way.
     */
    fun switchTrack(sb: StringBuilder, cx: Float, cy: Float, w: Float, h: Float, on: Boolean, active: Int, inactive: Int, compact: Boolean) {
        sb.append("left:").appendPx(cx - w / 2).append(";top:").appendPx(cy - h / 2)
            .append(";width:").appendPx(w).append(";height:").appendPx(h)
            .append(";border-radius:").appendPx(h / 2).append(';')
        when {
            compact -> sb.append("background-color:").appendColor(if (on) withAlpha(active, 0.5f) else withAlpha(inactive, 0.5f)).append(';')
            on -> sb.append("background-color:").appendColor(active).append(';')
            else -> sb.append("box-shadow:inset 0 0 0 2px ").appendColor(inactive).append(';')
        }
    }

    /** The thumb of a switch, in the track's box. */
    fun switchThumb(sb: StringBuilder, trackW: Float, trackH: Float, knob: Float, on: Boolean, color: Int, compact: Boolean) {
        val travel = if (compact) trackW - knob else trackW - trackH
        val x = if (compact) travel * (if (on) 1f else 0f) else (trackH - knob) / 2 + travel * (if (on) 1f else 0f)
        sb.append("left:").appendPx(x).append(";top:").appendPx((trackH - knob) / 2)
            .append(";width:").appendPx(knob).append(";height:").appendPx(knob)
            .append(";border-radius:50%;background-color:").appendColor(color).append(';')
        if (compact) sb.append("box-shadow:0 1px 3px rgba(0,0,0,.3);")
    }

    fun linearTrack(sb: StringBuilder, h: Float, track: Int) {
        sb.append("background-color:").appendColor(track).append(";overflow:hidden;border-radius:")
            .appendPx(h / 2).append(';')
    }

    /** The bar of a linear indicator: [value] 0-1, or null while indeterminate. */
    fun linearBar(sb: StringBuilder, value: Float?, color: Int) {
        sb.append("width:").appendNum1((value ?: 0.4f) * 100f).append("%;height:100%;")
        if (value == null) sb.append("left:30%;")
        sb.append("background-color:").appendColor(color).append(';')
    }

    /** A circular indicator's ring in a box of [w] x [h]. */
    fun circular(sb: StringBuilder, w: Float, h: Float, value: Float?, color: Int, track: Int?, stroke: Float = 4f) {
        val side = min(w, h)
        sb.append("left:").appendPx((w - side) / 2).append(";top:").appendPx((h - side) / 2)
            .append(";width:").appendPx(side).append(";height:").appendPx(side).append(";border-radius:50%;")
        if (value == null) {
            sb.append("border:").appendPx(stroke).append(" solid ")
            if (track == null) sb.append("transparent") else sb.appendColor(track)
            sb.append(";border-top-color:").appendColor(color).append(";border-right-color:").appendColor(color).append(';')
        } else {
            val pct = value * 100f
            sb.append("background-image:conic-gradient(").appendColor(color).append(" 0 ").appendNum1(pct).append("%,")
            if (track == null) sb.append("transparent") else sb.appendColor(track)
            sb.append(' ').appendNum1(pct).append("% 100%);")
            for (prefix in arrayOf("-webkit-mask", "mask")) {
                sb.append(prefix).append(":radial-gradient(farthest-side,transparent calc(100% - ").appendPx(stroke)
                    .append("),#000 calc(100% - ").appendPx(stroke).append("));")
            }
        }
    }

    const val TRACK_HEIGHT = 4f
    const val KNOB = 20f

    fun sliderTrack(sb: StringBuilder, left: Float, width: Float, cy: Float, color: Int) {
        sb.append("left:").appendPx(left).append(";top:").appendPx(cy - TRACK_HEIGHT / 2)
            .append(";width:").appendPx(max(0f, width)).append(";height:").appendPx(TRACK_HEIGHT)
            .append(";border-radius:2px;background-color:").appendColor(color).append(';')
    }

    fun sliderThumb(sb: StringBuilder, x: Float, cy: Float, color: Int) {
        sb.append("left:").appendPx(x - KNOB / 2).append(";top:").appendPx(cy - KNOB / 2)
            .append(";width:").appendPx(KNOB).append(";height:").appendPx(KNOB)
            .append(";border-radius:50%;background-color:").appendColor(color)
            .append(";box-shadow:0 1px 3px rgba(0,0,0,.3);")
    }
}

/**
 * What a view that draws something the wireframe cannot read shows on its
 * placeholder, from the names of its class and its parents' (a web view, a
 * map, a video).
 */
internal fun nativeViewLabel(name: String): String {
    val n = name.lowercase()
    if (n.contains("webview") || n.contains("web_view") || n.contains("browser")) return "Web view"
    if (n.contains("map")) return "Map"
    if (n.contains("video") || n.contains("player") || n.contains("exo")) return "Video"
    if (n.contains("camera") || n.contains("scanner") || n.contains("preview")) return "Camera"
    return "Native view"
}
