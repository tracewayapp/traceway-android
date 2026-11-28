package com.tracewayapp.traceway.wireframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireframeCssTest {
    @Test
    fun `lengths are rounded to a tenth and numbers lose their trailing zeros`() {
        assertEquals("12.3", num1(12.34f))
        assertEquals("-0.5", num1(-0.5f))
        assertEquals("3", num1(3f))
        assertEquals("0", num1(0.04f))
        assertEquals("0", num1(-0.04f))
        assertEquals("0", num1(Float.NaN))
        assertEquals("12.5px", px(12.46f))
        assertEquals("0.251", num3(64 / 255f))
        assertEquals("0.5", num3(0.5f))
        assertEquals("1", num3(1f))
        assertEquals("0.05", num3(0.05f))
        assertEquals("0.007", num3(0.007f))
        assertEquals("-0.25", num3(-0.25f))
    }

    @Test
    fun `colors are hex when opaque and rgba otherwise`() {
        assertEquals("#123456", cssColor(0xFF123456.toInt()))
        assertEquals("#00ff00", cssColor(0xFF00FF00.toInt()))
        assertEquals("rgba(0,0,0,0.251)", cssColor(0x40000000))
        assertTrue(isTransparent(0x00FFFFFF))
        assertEquals(0x80FF0000.toInt(), withAlpha(0xFFFF0000.toInt(), 0.5f) or 0x00000000)
    }

    @Test
    fun `corners and shadows`() {
        assertEquals("border-radius:8px;", StringBuilder().appendRadius(8f).toString())
        assertEquals("", StringBuilder().appendRadius(0f).toString())
        val uniform = floatArrayOf(4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f)
        assertEquals("border-radius:4px;", StringBuilder().appendRadii(uniform).toString())
        val top = floatArrayOf(8f, 8f, 8f, 8f, 0f, 0f, 0f, 0f)
        assertEquals("border-radius:8px 8px 0px 0px;", StringBuilder().appendRadii(top).toString())
        val elliptic = floatArrayOf(8f, 4f, 8f, 4f, 8f, 4f, 8f, 4f)
        assertEquals("border-radius:8px/4px;", StringBuilder().appendRadii(elliptic).toString())
        assertEquals("", StringBuilder().appendRadii(FloatArray(8)).toString())
        assertEquals(
            "box-shadow:0 2px 6.4px 0 rgba(0,0,0,0.278),inset 0 0 0 1px #0000ff;",
            StringBuilder().appendShadows(4f, 0xFF000000.toInt(), 1f, 0xFF0000FF.toInt()).toString(),
        )
        assertEquals("", StringBuilder().appendShadows(0f, 0xFF000000.toInt(), 0f, 0).toString())
    }

    @Test
    fun `gradients run clockwise from the top, like CSS`() {
        val ltr = StringBuilder().appendLinearGradient(0f, 0.5f, 1f, 0.5f, 100f, 50f, intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals("background-image:linear-gradient(90deg,#ffffff,#000000);", ltr.toString())
        val down = StringBuilder().appendLinearGradient(0.5f, 0f, 0.5f, 1f, 100f, 50f, intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        assertTrue(down.toString(), down.startsWith("background-image:linear-gradient(180deg,"))
    }

    @Test
    fun `halves round away from zero and lengths no screen has stay finite`() {
        assertEquals("12px", px(12f))
        assertEquals("12.3px", px(12.34f))
        assertEquals("12.4px", px(12.35f))
        assertEquals("1.3px", px(1.25f))
        assertEquals("-1.3px", px(-1.25f))
        assertEquals("0px", px(-0.04f))
        assertEquals("0px", px(Float.NaN))
        assertEquals("0.333", num3(0.33333f))
        assertEquals("0.063", num3(0.0625f))
        assertEquals("-0.063", num3(-0.0625f))
        assertEquals("0", num3(0.0004f))
        assertEquals("0", num3(-0.0004f))
        assertEquals("0", num3(Float.NaN))
        // A mirrored layout is written with mirrored numbers.
        for (v in listOf(0.05f, 0.25f, 1.25f, 7.45f, 12.35f, 99.95f)) {
            assertEquals("$v", "-" + num1(v), num1(-v))
            assertEquals("$v", "-" + num3(v / 100f), num3(-v / 100f))
        }
        // Infinities are no length; a finite one no screen has is clamped, not overflowed.
        assertEquals("0", num1(Float.POSITIVE_INFINITY))
        assertEquals("0", num1(Float.NEGATIVE_INFINITY))
        assertEquals("100000000", num1(Float.MAX_VALUE))
        assertEquals("-100000000px", px(-Float.MAX_VALUE))
        assertEquals("100000000", num3(Float.MAX_VALUE))
    }

    @Test
    fun `fills and transparency are decided in eight bits`() {
        assertEquals("#ff0000", cssColor(0xFFFF0000.toInt()))
        assertEquals("rgba(0,128,255,0.502)", cssColor(0x800080FF.toInt()))
        assertEquals("rgba(0,0,0,0.004)", cssColor(0x01000000))
        assertEquals("", StringBuilder().appendFill(0x00FFFFFF).toString())
        assertEquals("background-color:#ffffff;", StringBuilder().appendFill(0xFFFFFFFF.toInt()).toString())
        assertEquals("background-color:rgba(0,0,0,0.502);", StringBuilder().appendFill(0x80000000.toInt()).toString())
        assertTrue(isTransparent(0x00FFFFFF))
        assertTrue(!isTransparent(0x01000000))
        // The alpha is scaled and rounded to a byte; the color stays.
        assertEquals(0x80000000.toInt(), withAlpha(0xFF000000.toInt(), 0.5f))
        assertEquals(0x40FF0000, withAlpha(0x80FF0000.toInt(), 0.5f))
        assertEquals("a factor past 1 is 1", 0x80FF0000.toInt(), withAlpha(0x80FF0000.toInt(), 2f))
        assertEquals("below 0 is transparent", 0x00FF0000, withAlpha(0x80FF0000.toInt(), -1f))
    }

    @Test
    fun `unusable corners and shadows are left out`() {
        assertEquals("", StringBuilder().appendRadius(Float.NaN).toString())
        assertEquals("", StringBuilder().appendRadius(-4f).toString())
        assertEquals("", StringBuilder().appendRadius(Float.POSITIVE_INFINITY).toString())
        assertEquals("border-radius:4px;", StringBuilder().appendRadius(4.04f).toString())
        val unusable = floatArrayOf(Float.NaN, Float.NaN, -4f, -4f, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, 0f, 0f)
        assertEquals("", StringBuilder().appendRadii(unusable).toString())
        // One unusable corner is square; the others keep their radius.
        val oneNaN = floatArrayOf(6f, 6f, Float.NaN, Float.NaN, 6f, 6f, 6f, 6f)
        assertEquals("border-radius:6px 0px 6px 6px;", StringBuilder().appendRadii(oneNaN).toString())
        val oneNegative = floatArrayOf(6f, 6f, -2f, -2f, 6f, 6f, 6f, 6f)
        assertEquals("border-radius:6px 0px 6px 6px;", StringBuilder().appendRadii(oneNegative).toString())
        assertEquals("too few radii", "", StringBuilder().appendRadii(floatArrayOf(4f, 4f, 4f, 4f)).toString())
        val black = 0xFF000000.toInt()
        assertEquals("", StringBuilder().appendShadows(Float.NaN, black, Float.NaN, black).toString())
        assertEquals("", StringBuilder().appendShadows(4f, 0, 1f, 0x00FFFFFF).toString())
        assertEquals("box-shadow:inset 0 0 0 1px #000000;", StringBuilder().appendShadows(-2f, black, 1f, black).toString())
    }

    @Test
    fun `gradients carry their stops`() {
        val bw = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(
            "background-image:linear-gradient(90deg,#000000 0%,#ffffff 25%);",
            StringBuilder().appendLinearGradient(0f, 0.5f, 1f, 0.5f, 10f, 10f, bw, floatArrayOf(0f, 0.25f)).toString(),
        )
        // Fewer stops than colors: the rest go without one.
        assertEquals(
            "background-image:linear-gradient(180deg,#000000 50%,#ffffff);",
            StringBuilder().appendLinearGradient(0.5f, 0f, 0.5f, 1f, 10f, 10f, bw, floatArrayOf(0.5f)).toString(),
        )
        assertEquals("", StringBuilder().appendLinearGradient(0f, 0f, 1f, 1f, 10f, 10f, IntArray(0)).toString())
    }

    @Test
    fun `gradient angles run clockwise from the top in the box's proportions`() {
        val bw = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        fun angle(sx: Float, sy: Float, ex: Float, ey: Float, w: Float = 10f, h: Float = 10f) =
            StringBuilder().appendLinearGradient(sx, sy, ex, ey, w, h, bw).toString()
        assertTrue("bottom to top", angle(0.5f, 1f, 0.5f, 0f).contains("(0deg,"))
        assertTrue("right to left", angle(1f, 0.5f, 0f, 0.5f).contains("(270deg,"))
        assertTrue("a square's diagonal", angle(0f, 0f, 1f, 1f).contains("(135deg,"))
        // The same diagonal of a wide box is flatter.
        assertTrue(angle(0f, 0f, 1f, 1f, 30f, 10f), angle(0f, 0f, 1f, 1f, 30f, 10f).contains("(108.4deg,"))
        assertTrue(angle(1f, 1f, 0f, 0f), angle(1f, 1f, 0f, 0f).contains("(315deg,"))
    }

    @Test
    fun `radial and sweep gradients are placed at their center`() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0x800000FF.toInt())
        assertEquals(
            "background-image:radial-gradient(circle at 50% 25%,#ff0000,rgba(0,0,255,0.502));",
            StringBuilder().appendRadialGradient(0.5f, 0.25f, colors).toString(),
        )
        assertEquals(
            "background-image:conic-gradient(from 90deg at 50% 50%,#ff0000,rgba(0,0,255,0.502));",
            StringBuilder().appendSweepGradient(0.5f, 0.5f, colors).toString(),
        )
        assertEquals("", StringBuilder().appendRadialGradient(0f, 0f, IntArray(0)).toString())
        assertEquals("", StringBuilder().appendSweepGradient(0f, 0f, IntArray(0)).toString())
    }

    @Test
    fun `masks keep white space and line breaks`() {
        assertEquals("**** ****\n**", maskText("4242 4242\nab", StringBuilder()).toString())
        assertEquals("**\t*", maskText("a1\t%", StringBuilder()).toString())
        // The mask is written after what the builder holds.
        assertEquals("x:***", maskText("pin", StringBuilder("x:")).toString())
        assertEquals("", maskText("", StringBuilder()).toString())
    }

    @Test
    fun `text spills out of its box unless the app cut it`() {
        val look = TextLook()
        look.fontSize = 20f
        look.weight = 700
        look.color = 0xFF000000.toInt()
        var sb = StringBuilder()
        assertEquals("t n", sb.appendTextCss(look))
        assertTrue(sb.toString(), sb.contains("font-size:20px;") && sb.contains("font-weight:700;"))
        look.align = TextAlign.CENTER
        sb = StringBuilder()
        assertEquals("t n", sb.appendTextCss(look))
        assertTrue(sb.contains("justify-content:center"))
        look.clipped = true
        look.ellipsis = true
        assertEquals("t n e", StringBuilder().appendTextCss(look))
        look.lines = 3
        sb = StringBuilder()
        assertEquals("t e", sb.appendTextCss(look))
        assertTrue(sb.contains("text-align:center"))
        look.clipped = false
        assertEquals("t", StringBuilder().appendTextCss(look))
        assertEquals("*** ***", maskText("abc def", StringBuilder()).toString())
    }

    @Test
    fun `native views are labeled by their class`() {
        assertEquals("Web view", nativeViewLabel("android.webkit.WebView"))
        assertEquals("Map", nativeViewLabel("com.google.android.gms.maps.MapView"))
        assertEquals("Video", nativeViewLabel("androidx.media3.ui.PlayerView"))
        assertEquals("Camera", nativeViewLabel("androidx.camera.view.PreviewView"))
        assertEquals("Native view", nativeViewLabel("com.example.GameSurface"))
    }

    @Test
    fun `the document skeleton holds the shared rules`() {
        val screen = VNode.element(screenNodeId, "div", mapOf("id" to "tw"), emptyList())
        val doc = serializeDocument(screen).toString()
        assertTrue(doc.contains(".pv{"))
        assertTrue(wireframeCss.startsWith("html,body{"))
        assertTrue(wireframeCss.endsWith("}\n"))
    }

    @Test
    fun `the stylesheet is the one every platform shares, byte for byte`() {
        val shared = javaClass.getResource("/wireframe.css")!!.readText(Charsets.UTF_8)
        assertEquals(shared, wireframeCss)
    }

    @Test
    fun `the document and the nodes the recorder draws have the ids every platform uses`() {
        val screen = VNode.element(screenNodeId, "div", mapOf("id" to "tw"), emptyList())
        val ids = ArrayList<Int>()
        fun walk(node: Map<*, *>) {
            ids.add(node["id"] as Int)
            (node["childNodes"] as? List<*>)?.forEach { walk(it as Map<*, *>) }
        }
        walk(serializeDocument(screen))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), ids)
        assertEquals(listOf(9, 10, 12, 13, 14, 16), listOf(awayScreenTextBoxId, awayScreenTextId, keyboardNodeId, keyboardLabelBoxId, keyboardLabelTextId, firstCapturedNodeId))
        val away = WireframeRecorder.awayScreen(400f, 800f)
        assertEquals(screenNodeId, away.id)
        assertEquals(awayScreenTextBoxId, away.children[0].id)
        assertEquals(awayScreenTextId, away.children[0].children[0].id)
        assertEquals("tw", away.attributes["id"])
        assertTrue(allNodes(away).all { it.id < firstCapturedNodeId })
        assertEquals(listOf("App in the background"), texts(away))
    }
}
