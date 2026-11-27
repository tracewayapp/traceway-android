package com.tracewayapp.traceway.wireframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Pictures of the icons the app shows, so the replay draws each icon's shape
 * instead of a square, as the Flutter SDK does for icon fonts.
 *
 * The first time a vector icon shows at a size, it is drawn once into a
 * small PNG (encoded off the main thread); the replay uses it as a CSS mask
 * over the icon's color, so one picture serves every color the icon takes.
 * Until it is ready the icon is a square. Bitmaps (photos, avatars) are
 * never drawn.
 */
internal class WireframeGlyphs(
    private val onReady: () -> Unit,
    private val pixelRatio: Float = 2f,
    private val maxGlyphs: Int = 300,
) {
    class Glyph(val cls: String, val wPx: Int, val hPx: Int) {
        /** `ic <cls>`: the class an icon with this glyph carries. */
        val iconClass = "ic $cls"

        /** The rule that shows it, once drawn. */
        @Volatile
        var css: String? = null
        var node: VNode? = null
        var lastShownMs = Long.MIN_VALUE / 2
    }

    private class Key(val state: Any, val w: Int, val h: Int) {
        override fun equals(other: Any?): Boolean = other is Key && other.state === state && other.w == w && other.h == h
        override fun hashCode(): Int = (System.identityHashCode(state) * 31 + w) * 31 + h
    }

    private val byDrawable = WeakHashMap<Drawable, Glyph>()
    private val byKey = HashMap<Key, Glyph>()

    /**
     * Glyphs by what they draw: a picture is a mask, so the same icon tinted
     * another color (a mutated copy, with a state of its own) is one glyph.
     */
    private val byShape = HashMap<Long, Glyph>()
    val all = ArrayList<Glyph>()

    /** Glyphs on the screen being captured. */
    val shown = ArrayList<Glyph>()

    @Volatile
    private var executorStarted = false

    private val executor: Executor by lazy {
        executorStarted = true
        Executors.newSingleThreadExecutor { r -> Thread(r, "TracewayGlyphs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** Stops the encoder thread; glyphs drawn later stay squares. */
    fun dispose() {
        try {
            if (executorStarted) (executor as? java.util.concurrent.ExecutorService)?.shutdown()
        } catch (_: Throwable) {
        }
    }

    /** The glyph of [d] drawn at [wPx] x [hPx], drawn now if it is new; null when too many were drawn. */
    fun lookup(d: Drawable, wPx: Int, hPx: Int, density: Float): Glyph? {
        if (wPx <= 0 || hPx <= 0) return null
        byDrawable[d]?.let { if (it.wPx == wPx && it.hPx == hPx) return it }
        val key = Key(d.constantState ?: d, wPx, hPx)
        var g = byKey[key]
        if (g == null) {
            if (all.size >= maxGlyphs) return null
            val bitmap = draw(d, wPx, hPx, density)
            val shape = bitmap?.let { shapeOf(it) }
            g = shape?.let { byShape[it] }
            if (g != null) {
                bitmap?.recycle()
            } else {
                g = Glyph("g${all.size}", wPx, hPx)
                all.add(g)
                if (shape != null) byShape[shape] = g
                if (bitmap != null) encode(g, bitmap)
            }
            byKey[key] = g
        }
        byDrawable[d] = g
        return g
    }

    /** [d] drawn at its bounds, [wPx] x [hPx] at the glyphs' pixel ratio; null when it cannot be. */
    private fun draw(d: Drawable, wPx: Int, hPx: Int, density: Float): Bitmap? {
        val scale = pixelRatio / density
        val bw = ceil(wPx * scale).toInt()
        val bh = ceil(hPx * scale).toInt()
        if (bw <= 0 || bh <= 0 || bw > 256 || bh > 256) return null
        return try {
            val bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            c.scale(scale, scale)
            val b = d.bounds
            c.translate(-b.left.toFloat(), -b.top.toFloat())
            d.draw(c)
            bm
        } catch (_: Throwable) {
            null
        }
    }

    /** A hash of where [bitmap] is opaque: its shape, whatever its color. */
    private fun shapeOf(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var hash = 1125899906842597L
        hash = hash * 31 + w
        hash = hash * 31 + h
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) hash = hash * 31 + ((p ushr 24) shr 3)
        }
        return hash
    }

    /** Encodes [bitmap] as [g]'s picture, off the main thread. */
    private fun encode(g: Glyph, bitmap: Bitmap) {
        try {
            executor.execute {
                try {
                    val png = ByteArrayOutputStream(1024)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
                    val data = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
                    g.css = ".ic.${g.cls}::before{inset:0;border-radius:0;opacity:1;" +
                        "mask:url(data:image/png;base64,$data) 0 0/100% 100% no-repeat}"
                    onReady()
                } catch (_: Throwable) {
                    // Without its picture the icon stays a square.
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: Throwable) {
            // No encoder thread (out of memory, or disposed): the icon stays a square.
            bitmap.recycle()
        }
    }
}
