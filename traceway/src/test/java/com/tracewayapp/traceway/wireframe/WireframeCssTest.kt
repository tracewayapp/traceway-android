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
    }
}
