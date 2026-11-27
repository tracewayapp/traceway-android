package com.tracewayapp.traceway.wireframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.Base64
import com.tracewayapp.traceway.TracewayImagePolicy
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

/** How much of its replay image budget a session has left; asked on any thread. */
internal interface ImageBudget {
    fun hasRoom(): Boolean

    /** Counts [bytes] of encoded image data; false when they do not fit. */
    fun admit(bytes: Int): Boolean
}

/**
 * The pictures image views show, recorded into the wireframe as the
 * replay image policy allows (`replay.images`, capped by
 * `TracewayOptions.replayImages`): the bitmap of an `ImageView`'s
 * `BitmapDrawable` (the top bitmap of a layer drawable, as a crossfade
 * shows it) and bitmap backgrounds, never a screenshot of the window, never
 * under a mask.
 *
 * A picture is copied at the size it shows (1.5 times its dp size, at most
 * [MAX_SIDE_PX] on its longest side, never larger than the bitmap) on the
 * main thread, while the capture looks at it: the app's image loader may
 * recycle or reuse its bitmap at any time after, so only that copy goes to
 * the encoder (a recycled bitmap read on another thread aborts the
 * process). The copy is encoded off the main thread: JPEG at quality 0.6,
 * PNG when it has alpha and is at most [PNG_MAX_SIDE_PX]. One that still
 * takes more than [MAX_BYTES] at half that size is left out, and so is one
 * that cannot be copied (out of memory; a hardware bitmap before API 28).
 * At most [MAX_COPIES_PER_FRAME] copies per frame and [MAX_PENDING_BYTES]
 * of copies waiting for the encoder; past either, a picture waits for a
 * later frame. Until it is ready its view
 * shows the `im` placeholder; then it is a style node first in the screen,
 * `.im.p<N>{background:url(data:…) center/cover no-repeat}`, that the view's
 * node names with the classes `im p<N>`. The same bitmap at the same size
 * is one picture however many views show it.
 *
 * At most [maxImages] pictures are kept: past that, those not shown for
 * [LINGER_MS] (whose styles are off the page) make room, and a new session
 * starts the set again ([newSession]). [ImageBudget] bytes per session;
 * past it, new pictures are placeholders. Whether a frame may record pictures
 * is decided when it is captured ([beginCapture]): a picture recorded on
 * Wi-Fi stays in that clip.
 */
internal class WireframeImages(
    private val onReady: () -> Unit,
    private val maxImages: Int = 64,
    private val encoder: Executor? = null,
) {
    class Image(
        val cls: String,
        internal val generation: Int,
        val wPx: Int,
        val hPx: Int,
        /** When it was first seen, on the capture clock: a picture still encoding is not one that lingers. */
        internal val addedMs: Long = 0L,
    ) {
        /** `im <cls>`: the classes of a view showing it. */
        val imageClass = "im $cls"

        /** The rule that shows it, once encoded. */
        @Volatile
        var css: String? = null

        /** Encoding failed, it was too big, or the budget was used up: a placeholder for good. */
        @Volatile
        var failed = false

        /** The encoded size in bytes, once encoded. */
        @Volatile
        var bytes = 0
        var node: VNode? = null
        var lastShownMs = Long.MIN_VALUE / 2
    }

    /** The policy now: set by the client from the collection settings. */
    @Volatile
    var policy: TracewayImagePolicy = TracewayImagePolicy.OFF

    /** Whether the connection is unmetered, for [TracewayImagePolicy.WIFI]. */
    @Volatile
    var unmetered: () -> Boolean = { false }

    /** The session's image budget; null records pictures without one (tests). */
    @Volatile
    var budget: ImageBudget? = null

    private val bySource = WeakHashMap<Bitmap, ArrayList<Image>>()
    val all = ArrayList<Image>()

    /** Pictures on the screen being captured. */
    val shown = ArrayList<Image>()

    /** Whether the frame being captured may record pictures; see [beginCapture]. */
    var allowed = false
        private set

    /** The capture clock of the frame being captured. */
    private var nowMs = 0L

    /** The number in the class of the next new picture: never reused, so a class always names one picture. */
    private var nextIndex = 0
    private var copiesThisFrame = 0

    /** Bytes of copies waiting for the encoder; decreased on its thread. */
    private val pendingBytes = AtomicLong()

    @Volatile
    private var newSession = false

    @Volatile
    private var executorStarted = false

    private val executor: Executor by lazy {
        executorStarted = true
        encoder ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "TracewayImages").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /** A new session: its pictures are recorded again, against its own budget. Any thread; applied at the next frame. */
    fun newSession() {
        newSession = true
    }

    /**
     * Decides, once per frame, whether this frame records pictures: the
     * policy, and the connection for Wi-Fi. [nowMs] is the frame's time on
     * the capture clock. Main thread.
     */
    fun beginCapture(nowMs: Long = System.currentTimeMillis()) {
        this.nowMs = nowMs
        copiesThisFrame = 0
        if (newSession) {
            newSession = false
            all.clear()
            bySource.clear()
        }
        shown.clear()
        allowed = try {
            when (policy) {
                TracewayImagePolicy.OFF -> false
                TracewayImagePolicy.WIFI -> unmetered()
                TracewayImagePolicy.ALWAYS -> true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The picture of [bitmap] shown [wDp] x [hDp], to use in this frame: its
     * class once encoded, null while it is being encoded or when it cannot
     * be recorded. Starts encoding a picture seen for the first time. Main
     * thread, inside a capture that [allowed] pictures.
     */
    fun lookup(bitmap: Bitmap, wDp: Float, hDp: Float): Image? {
        // A box of no size, or of a NaN or infinite one, shows no picture.
        if (!allowed || !(wDp > 0f && wDp < Float.POSITIVE_INFINITY && hDp > 0f && hDp < Float.POSITIVE_INFINITY)) return null
        val generation = try {
            if (bitmap.isRecycled) return null
            bitmap.generationId
        } catch (_: Throwable) {
            return null
        }
        val tw = target(wDp, hDp, bitmap.width, bitmap.height, true)
        val th = target(wDp, hDp, bitmap.width, bitmap.height, false)
        if (tw <= 0 || th <= 0) return null
        val known = bySource.getOrPut(bitmap) { ArrayList(1) }
        var image = known.firstOrNull { it.generation == generation && it.wPx == tw && it.hPx == th }
        if (image == null) {
            // A picture already encoded costs nothing more; a new one needs room in the session's budget.
            if (budget?.hasRoom() == false) return null
            if (all.size >= maxImages && !evictLingering()) return null
            // Copies are bounded per frame and while the encoder is behind: this picture waits for a later frame.
            val bytes = tw.toLong() * th * 4
            if (copiesThisFrame >= MAX_COPIES_PER_FRAME || pendingBytes.get() + bytes > MAX_PENDING_BYTES) return null
            copiesThisFrame++
            image = Image("p${nextIndex++}", generation, tw, th, nowMs)
            all.add(image)
            // A pooled bitmap that now holds another picture is a new one.
            known.removeAll { it.generation != generation }
            known.add(image)
            val alpha = try {
                bitmap.hasAlpha()
            } catch (_: Throwable) {
                true
            }
            val copy = copyOf(bitmap, tw, th)
            if (copy == null) {
                image.failed = true
            } else {
                encode(image, copy, alpha, bytes)
            }
            return null
        }
        if (image.failed || image.css == null) return null
        shown.add(image)
        return image
    }

    /** A side of the copy: the shown size at 1.5 px per dp, the longest side at most [MAX_SIDE_PX], no larger than the bitmap. */
    private fun target(wDp: Float, hDp: Float, bw: Int, bh: Int, width: Boolean): Int {
        var w = wDp * SCALE
        var h = hDp * SCALE
        val longest = max(w, h)
        if (longest > MAX_SIDE_PX) {
            w *= MAX_SIDE_PX / longest
            h *= MAX_SIDE_PX / longest
        }
        if (w > bw || h > bh) {
            val f = minOf(bw / w, bh / h)
            w *= f
            h *= f
        }
        return max(1, ceil(if (width) w else h).toInt())
    }

    /**
     * Forgets the pictures not shown for [LINGER_MS] (their styles are no
     * longer on the page), to make room for a new one; false when none
     * lingers. Main thread.
     */
    private fun evictLingering(): Boolean {
        val cutoff = nowMs - LINGER_MS
        val gone = HashSet<Image>()
        for (image in all) {
            if (maxOf(image.lastShownMs, image.addedMs) < cutoff && image !in shown) gone.add(image)
        }
        if (gone.isEmpty()) return false
        all.removeAll(gone)
        for (list in bySource.values) list.removeAll(gone)
        return true
    }

    /**
     * The app's [source] drawn at [w] x [h] into a bitmap of the SDK's own,
     * on the calling (main) thread; null when it cannot be (out of memory, a
     * hardware bitmap before API 28): the picture is left out, never the app.
     */
    private fun copyOf(source: Bitmap, w: Int, h: Int): Bitmap? = try {
        when {
            source.isRecycled -> null
            Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE ->
                if (Build.VERSION.SDK_INT >= 28) hardwareCopy(source, w, h) else null
            else -> {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(source, null, Rect(0, 0, w, h), FILTER)
                out
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** A hardware bitmap cannot be drawn into a software canvas: it is drawn into a picture, rendered at [w] x [h]. */
    @androidx.annotation.RequiresApi(28)
    private fun hardwareCopy(source: Bitmap, w: Int, h: Int): Bitmap? {
        val picture = Picture()
        picture.beginRecording(w, h).drawBitmap(source, null, Rect(0, 0, w, h), FILTER)
        picture.endRecording()
        return Bitmap.createBitmap(picture, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Encodes [copy], the SDK's own bitmap, off the main thread, and recycles it. */
    private fun encode(image: Image, copy: Bitmap, alpha: Boolean, bytes: Long) {
        pendingBytes.addAndGet(bytes)
        try {
            executor.execute {
                try {
                    val encoded = encoded(copy, alpha, image.wPx, image.hPx)
                        ?: encoded(copy, alpha, max(1, image.wPx / 2), max(1, image.hPx / 2))
                    val b = budget
                    if (encoded == null || (b != null && !b.admit(encoded.second.size))) {
                        image.failed = true
                        return@execute
                    }
                    image.bytes = encoded.second.size
                    val data = Base64.encodeToString(encoded.second, Base64.NO_WRAP)
                    image.css = ".im.${image.cls}{background:url(data:${encoded.first};base64,$data) center/cover no-repeat}"
                    onReady()
                } catch (_: Throwable) {
                    image.failed = true
                } finally {
                    release(copy, bytes)
                }
            }
        } catch (_: Throwable) {
            image.failed = true
            release(copy, bytes)
        }
    }

    private fun release(copy: Bitmap, bytes: Long) {
        pendingBytes.addAndGet(-bytes)
        try {
            copy.recycle()
        } catch (_: Throwable) {
        }
    }

    /** [copy] at [w] x [h], as its MIME type and bytes; null when it takes more than [MAX_BYTES] or cannot be encoded. */
    private fun encoded(copy: Bitmap, alpha: Boolean, w: Int, h: Int): Pair<String, ByteArray>? {
        val scaled = if (copy.width == w && copy.height == h) copy else Bitmap.createScaledBitmap(copy, w, h, true)
        try {
            val png = alpha && max(w, h) <= PNG_MAX_SIDE_PX
            val out = ByteArrayOutputStream(16 * 1024)
            if (!scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else JPEG_QUALITY, out)) {
                return null
            }
            if (out.size() > MAX_BYTES) return null
            return (if (png) "image/png" else "image/jpeg") to out.toByteArray()
        } finally {
            if (scaled !== copy) scaled.recycle()
        }
    }

    /** Stops the encoder thread; pictures looked up later are left out. */
    fun dispose() {
        try {
            if (encoder == null && executorStarted) (executor as? java.util.concurrent.ExecutorService)?.shutdown()
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Pixels per dp of a picture's copy. */
        const val SCALE = 1.5f
        const val MAX_SIDE_PX = 512f
        const val PNG_MAX_SIDE_PX = 128
        const val JPEG_QUALITY = 60
        const val MAX_BYTES = 96 * 1024

        /** New copies one frame makes at most, so a screen full of new pictures does not hold up the main thread. */
        const val MAX_COPIES_PER_FRAME = 8

        /** Bytes of copies waiting for the encoder at most (four of the largest). */
        const val MAX_PENDING_BYTES = 4L * 1024 * 1024

        private val FILTER = Paint(Paint.FILTER_BITMAP_FLAG)

        /** How long a picture's style stays on the page after its view left the screen. */
        const val LINGER_MS = 30_000L

        /**
         * The bitmap [d] shows: a `BitmapDrawable`'s, or the top one in a
         * layer drawable (the new picture of a crossfade). Null for anything
         * else (vectors, shapes, animated images).
         */
        fun bitmapOf(d: Drawable, depth: Int = 0): Bitmap? {
            val leaf = WireframeCapture.unwrap(d) ?: return null
            if (leaf is BitmapDrawable) return leaf.bitmap
            if (leaf is LayerDrawable && leaf !is RippleDrawable && depth < 3) {
                for (i in leaf.numberOfLayers - 1 downTo 0) {
                    val layer = leaf.getDrawable(i) ?: continue
                    bitmapOf(layer, depth + 1)?.let { return it }
                }
            }
            return null
        }
    }
}
