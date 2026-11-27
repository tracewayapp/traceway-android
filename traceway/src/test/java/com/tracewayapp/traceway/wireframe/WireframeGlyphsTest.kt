package com.tracewayapp.traceway.wireframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pictures icons are drawn with ([WireframeGlyphs]): a PNG of the
 * icon's shape at 2 px per dp, used as a CSS mask over the icon's color,
 * drawn once per icon and size. What icon views become is in
 * [ViewWireframeTest]; where an image sits in its view is in
 * [WireframeImagesTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WireframeGlyphsTest {
    private enum class Shape { LEFT_HALF, TOP_HALF, DOT }

    /** An icon that paints [shape] in [color] over its bounds; it counts its drawings in its shared state. */
    private class Icon(private val state: State) : Drawable() {
        class State(val shape: Shape, val color: Int, val throws: Boolean = false) : ConstantState() {
            var draws = 0
            override fun newDrawable(): Drawable = Icon(this)
            override fun getChangingConfigurations(): Int = 0
        }

        constructor(shape: Shape, color: Int = Color.BLACK, throws: Boolean = false) : this(State(shape, color, throws))

        private val paint = Paint().apply { color = state.color }
        val draws: Int get() = state.draws

        override fun getConstantState(): ConstantState = state

        override fun draw(canvas: Canvas) {
            state.draws++
            if (state.throws) throw IllegalStateException("a broken icon")
            val b = bounds
            when (state.shape) {
                Shape.LEFT_HALF -> canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.exactCenterX(), b.bottom.toFloat(), paint)
                Shape.TOP_HALF -> canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.exactCenterY(), paint)
                Shape.DOT -> canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() / 4f, paint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun icon(shape: Shape, color: Int = Color.BLACK, side: Int = 24, throws: Boolean = false) =
        Icon(shape, color, throws).apply { setBounds(0, 0, side, side) }

    private val ready = AtomicInteger()
    private fun glyphs(maxGlyphs: Int = 300) = WireframeGlyphs(onReady = { ready.incrementAndGet() }, maxGlyphs = maxGlyphs)

    /** [glyph]'s rule once it is drawn, off the main thread. */
    private fun drawn(glyph: WireframeGlyphs.Glyph): String {
        val deadline = System.currentTimeMillis() + 5000
        while (glyph.css == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return glyph.css ?: error("${glyph.cls} was never drawn")
    }

    /** How many glyphs said they are ready, once [expected] have (the call comes just after the rule is set). */
    private fun readyCount(expected: Int): Int {
        val deadline = System.currentTimeMillis() + 5000
        while (ready.get() < expected && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return ready.get()
    }

    private fun png(css: String): ByteArray = Base64.decode(Regex("base64,([^)]+)\\)").find(css)!!.groupValues[1], 0)

    private fun picture(css: String): Bitmap {
        val bytes = png(css)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    @Test
    fun `the glyph is a PNG of the icon at two pixels per dp`() {
        val g = glyphs().lookup(icon(Shape.LEFT_HALF), 24, 24, 1f)!!
        assertEquals("g0", g.cls)
        assertEquals(24, g.wPx)
        assertEquals(24, g.hPx)
        val bytes = png(drawn(g))
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.take(8).map { it.toInt() and 0xff },
        )
        // IHDR: 48 x 48.
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(listOf(0, 0, 0, 48, 0, 0, 0, 48), bytes.slice(16 until 24).map { it.toInt() and 0xff })
        assertEquals("IEND", String(bytes, bytes.size - 8, 4, Charsets.US_ASCII))
        assertEquals(1, readyCount(1))
    }

    @Test
    fun `the picture holds where the icon covers, wherever its bounds are`() {
        val d = Icon(Shape.LEFT_HALF, Color.RED).apply { setBounds(10, 30, 34, 54) }
        val bitmap = picture(drawn(glyphs().lookup(d, 24, 24, 1f)!!))
        assertEquals(48, bitmap.width)
        assertEquals(48, bitmap.height)
        for (y in listOf(1, 24, 46)) {
            assertEquals("covered at 2,$y", 255, Color.alpha(bitmap.getPixel(2, y)))
            assertEquals("covered at 21,$y", 255, Color.alpha(bitmap.getPixel(21, y)))
            assertEquals("empty at 27,$y", 0, Color.alpha(bitmap.getPixel(27, y)))
            assertEquals("empty at 46,$y", 0, Color.alpha(bitmap.getPixel(46, y)))
        }
    }

    @Test
    fun `the rule masks the icon square`() {
        val g = glyphs().lookup(icon(Shape.DOT), 24, 24, 1f)!!
        assertEquals("ic g0", g.iconClass)
        val css = drawn(g)
        assertTrue(css, css.startsWith(".ic.g0::before{inset:0;border-radius:0;opacity:1;mask:url(data:image/png;base64,"))
        assertTrue(css, css.endsWith(") 0 0/100% 100% no-repeat}"))
        assertTrue("one rule, nothing after it", css.indexOf('}') == css.length - 1)
    }

    @Test
    fun `unusable sizes and icons get no picture`() {
        val glyphs = glyphs()
        assertNull(glyphs.lookup(icon(Shape.DOT), 0, 24, 1f))
        assertNull(glyphs.lookup(icon(Shape.DOT), 24, -1, 1f))
        // Wider than 256 px at 2 px per dp, or an icon that throws while it is drawn:
        // a glyph without a picture, so the icon stays a square.
        val wide = glyphs.lookup(icon(Shape.DOT, side = 129), 129, 24, 1f)!!
        val broken = glyphs.lookup(icon(Shape.DOT, throws = true), 24, 24, 1f)!!
        val largest = glyphs.lookup(icon(Shape.TOP_HALF, side = 128), 128, 128, 1f)!!
        assertEquals(256, picture(drawn(largest)).width)
        assertEquals("only the largest was ready", 1, readyCount(1))
        assertNull(wide.css)
        assertNull(broken.css)
        assertEquals(listOf("g0", "g1", "g2"), glyphs.all.map { it.cls })
    }

    @Test
    fun `the same icon in another color is one glyph`() {
        val glyphs = glyphs()
        val red = glyphs.lookup(icon(Shape.LEFT_HALF, Color.RED), 24, 24, 1f)!!
        val blue = glyphs.lookup(icon(Shape.LEFT_HALF, Color.BLUE), 24, 24, 1f)!!
        val other = glyphs.lookup(icon(Shape.TOP_HALF, Color.RED), 24, 24, 1f)!!
        assertSame("a picture is a mask: color is not part of it", red, blue)
        assertNotSame(red, other)
        assertEquals(listOf("g0", "g1"), glyphs.all.map { it.cls })
        drawn(red)
        drawn(other)
        assertEquals(2, readyCount(2))
    }

    @Test
    fun `an icon is drawn once per size`() {
        val glyphs = glyphs()
        val a = icon(Shape.DOT)
        val g = glyphs.lookup(a, 24, 24, 1f)!!
        assertSame(g, glyphs.lookup(a, 24, 24, 1f))
        // Another drawable of the same icon (the same constant state) is not drawn again.
        val b = a.constantState.newDrawable().apply { setBounds(0, 0, 24, 24) }
        assertSame(g, glyphs.lookup(b, 24, 24, 1f))
        assertEquals(1, a.draws)
        // The same icon at another size is another picture.
        a.setBounds(0, 0, 48, 48)
        val big = glyphs.lookup(a, 48, 48, 1f)!!
        assertNotSame(g, big)
        assertEquals(48, big.wPx)
        assertEquals(2, a.draws)
        assertEquals(96, picture(drawn(big)).width)
        assertSame("the smaller one is still known", g, glyphs.lookup(b, 24, 24, 1f))
        assertEquals(2, a.draws)
    }

    @Test
    fun `glyphs are drawn at two pixels per dp on every density`() {
        val glyphs = glyphs()
        // 24 dp on a 3x screen: 72 px, drawn into 48.
        val d = icon(Shape.LEFT_HALF, side = 72)
        val g = glyphs.lookup(d, 72, 72, 3f)!!
        assertEquals(72, g.wPx)
        val bitmap = picture(drawn(g))
        assertEquals(48, bitmap.width)
        assertEquals(48, bitmap.height)
        assertEquals(255, Color.alpha(bitmap.getPixel(10, 24)))
        assertEquals(0, Color.alpha(bitmap.getPixel(40, 24)))
        // The limit is in dp too: 133 dp is more than 256 px at 2 px per dp, whatever the density.
        val large = glyphs.lookup(icon(Shape.DOT, side = 400), 400, 400, 3f)!!
        drawn(glyphs.lookup(icon(Shape.TOP_HALF, side = 72), 72, 72, 3f)!!)
        assertNull(large.css)
    }

    @Test
    fun `past the glyph limit new icons get none`() {
        val glyphs = glyphs(maxGlyphs = 2)
        val firstIcon = icon(Shape.LEFT_HALF)
        val first = glyphs.lookup(firstIcon, 24, 24, 1f)
        assertNotNull(first)
        assertNotNull(glyphs.lookup(icon(Shape.TOP_HALF), 24, 24, 1f))
        assertNull(glyphs.lookup(icon(Shape.DOT), 24, 24, 1f))
        assertEquals(2, glyphs.all.size)
        // The ones already drawn are still there.
        assertSame(first, glyphs.lookup(firstIcon, 24, 24, 1f))
        val again = icon(Shape.LEFT_HALF)
        assertNull("a new drawable past the limit is not drawn, even of a known shape", glyphs.lookup(again, 24, 24, 1f))
        assertEquals(0, again.draws)
    }
}
