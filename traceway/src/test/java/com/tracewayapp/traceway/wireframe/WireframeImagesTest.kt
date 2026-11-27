package com.tracewayapp.traceway.wireframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.tracewayapp.traceway.TracewayImagePolicy
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.tracewayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random
import java.util.concurrent.Executor

/**
 * Pictures in the wireframe: what image views show, recorded as the replay
 * image policy, the connection, masks and the session's budget
 * allow, and otherwise placeholders. The screen is 400 x 800 dp at 1 px per
 * dp. Topics 16 (inlined when the policy is always), 17 (`wifi` on a
 * metered network) and 18 (dedup and the session's budget) of the wireframe
 * suites every SDK shares; 15 is in [ViewWireframeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WireframeImagesTest {
    private fun photo(w: Int = 800, h: Int = 400, color: Int = Color.rgb(200, 40, 40)): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            setHasAlpha(false)
        }

    private fun imageView(a: Context, bitmap: Bitmap, w: Int, h: Int, scale: ImageView.ScaleType = ImageView.ScaleType.FIT_XY) =
        ImageView(a).apply {
            setImageDrawable(BitmapDrawable(a.resources, bitmap))
            scaleType = scale
            layoutParams = LinearLayout.LayoutParams(w, h)
        }

    private fun column(a: Context, vararg views: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        for (v in views) addView(v)
    }

    private fun RealScreen.policy(policy: TracewayImagePolicy, unmetered: Boolean = true): RealScreen {
        capture.images.policy = policy
        capture.images.unmetered = { unmetered }
        return this
    }

    /** The screen once every picture the last frames asked for is encoded (or given up). */
    private fun RealScreen.settled(): VNode {
        screen()
        val deadline = System.currentTimeMillis() + 5000
        while (capture.images.all.any { it.css == null && !it.failed } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return screen()
    }

    private fun classes(screen: VNode): List<String> =
        allNodes(screen).mapNotNull { it.attributes["class"] }.filter { it == "im" || it.startsWith("im ") }.toList()

    private fun pictureStyles(screen: VNode): List<String> =
        screen.children.filter { it.tag == "style" }.mapNotNull { it.attributes["_cssText"] }.filter { it.startsWith(".im.") }

    @Test
    fun withImagesOffAPhotoIsAPlaceholder() {
        val s = RealScreen.open { a -> column(a, imageView(a, photo(), 200, 100)) }.policy(TracewayImagePolicy.OFF)
        val screen = s.settled()
        assertEquals(listOf("im"), classes(screen))
        assertTrue(pictureStyles(screen).isEmpty())
        assertTrue("nothing is even encoded", s.capture.images.all.isEmpty())
    }

    @Test
    fun imagesAreInlinedWhenThePolicyIsAlways() {
        lateinit var backdrop: FrameLayout
        val s = RealScreen.open { a ->
            backdrop = FrameLayout(a).apply {
                background = BitmapDrawable(a.resources, photo(color = Color.BLUE))
                layoutParams = LinearLayout.LayoutParams(300, 60)
            }
            column(
                a,
                imageView(a, photo(), 200, 100),
                imageView(a, photo(color = Color.GREEN), 100, 100, ImageView.ScaleType.CENTER_CROP),
                backdrop,
            )
        }.policy(TracewayImagePolicy.ALWAYS)
        // The first frame shows placeholders while the pictures are encoded.
        assertEquals(listOf("im", "im"), classes(s.screen()))

        val screen = s.settled()
        val styles = pictureStyles(screen)
        assertEquals(3, styles.size)
        assertTrue(styles[0], styles[0].startsWith(".im.p0{background:url(data:image/jpeg;base64,"))
        assertTrue(styles[0], styles[0].endsWith(") center/cover no-repeat}"))
        val jpeg = android.util.Base64.decode(Regex("base64,([^)]+)\\)").find(styles[0])!!.groupValues[1], 0)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        // Copied at one pixel per dp of where it shows.
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(200, decoded.width)
        assertEquals(100, decoded.height)

        val pictures = allNodes(screen).filter { (it.attributes["class"] ?: "").startsWith("im p") }.toList()
        assertEquals(listOf("im p0", "im p1", "im p2"), pictures.map { it.attributes["class"] })
        assertTrue(style(pictures[0]), style(pictures[0]).contains("background-size:200px 100px;background-position:0px 0px;"))
        // CENTER_CROP: the picture is as tall as the view and wider, centered; the view shows its middle.
        assertTrue(style(pictures[1]), style(pictures[1]).contains("background-size:200px 100px;background-position:-50px 0px;"))
        assertEquals(100f, styleNumber(pictures[1], "width"))
        // A bitmap background is a picture under the view's content, without the placeholder tint.
        assertTrue(style(pictures[2]), style(pictures[2]).contains("width:300px;height:60px;background-size:100% 100%;"))
        assertTrue(styles(screen).none { it.contains("rgba(158,158,158,.22)") })
        // The style nodes come first in the screen, after nothing but other styles.
        assertTrue(screen.children.takeWhile { it.tag == "style" }.size >= 3)
    }

    @Test
    fun wifiOnAMeteredNetworkGivesPlaceholders() {
        val s = RealScreen.open { a -> column(a, imageView(a, photo(), 200, 100)) }.policy(TracewayImagePolicy.WIFI, unmetered = false)
        assertEquals(listOf("im"), classes(s.settled()))
        assertTrue(s.capture.images.all.isEmpty())
        // On Wi-Fi again, the next frames record the picture.
        s.capture.images.unmetered = { true }
        val screen = s.settled()
        assertEquals(listOf("im p0"), classes(screen))
        assertEquals(1, pictureStyles(screen).size)
    }

    @Test
    fun aMaskedPictureStaysAPlaceholder() {
        val s = RealScreen.open { a ->
            column(
                a,
                FrameLayout(a).apply {
                    tracewayMask = TracewayMaskMode.blur()
                    addView(imageView(a, photo(), 200, 100))
                },
                imageView(a, photo(color = Color.GREEN), 50, 50),
            )
        }.policy(TracewayImagePolicy.ALWAYS)
        val screen = s.settled()
        assertEquals(listOf("im", "im p0"), classes(screen))
        assertEquals("the masked bitmap is never encoded", 1, s.capture.images.all.size)
    }

    @Test
    fun imagesAreDedupedAcrossViews() {
        val shared = photo()
        val s = RealScreen.open { a ->
            column(a, imageView(a, shared, 200, 100), imageView(a, shared, 200, 100), imageView(a, shared, 100, 50))
        }.policy(TracewayImagePolicy.ALWAYS)
        val screen = s.settled()
        assertEquals(listOf("im p0", "im p0", "im p1"), classes(screen))
        assertEquals(2, pictureStyles(screen).size)
    }

    @Test
    fun imagesKeepToTheSessionBudget() {
        var room = 1
        val admitted = ArrayList<Int>()
        lateinit var holder: LinearLayout
        lateinit var context: Context
        val s = RealScreen.open { a ->
            context = a
            holder = column(a, imageView(a, photo(), 200, 100))
            holder
        }.policy(TracewayImagePolicy.ALWAYS)
        s.capture.images.budget = object : ImageBudget {
            override fun hasRoom(): Boolean = room > 0
            override fun admit(bytes: Int): Boolean {
                if (room <= 0) return false
                room--
                admitted.add(bytes)
                return true
            }
        }
        assertEquals(listOf("im p0"), classes(s.settled()))
        assertEquals(1, admitted.size)
        assertTrue(admitted.single() in 1..WireframeImages.MAX_BYTES)

        holder.addView(imageView(context, photo(color = Color.GREEN), 200, 100))
        val screen = s.settled()
        assertEquals("the one recorded stays, the new one is not", listOf("im p0", "im"), classes(screen))
        assertEquals(1, s.capture.images.all.size)
        assertEquals(1, pictureStyles(screen).size)
    }

    // ---- the policy, without a screen ---------------------------------------------

    /** Pictures recorded as [policy] allows, encoded by [encoder] (by default never). */
    private fun images(policy: TracewayImagePolicy = TracewayImagePolicy.ALWAYS, encoder: Executor = Executor { }) =
        WireframeImages(onReady = {}, encoder = encoder).apply {
            this.policy = policy
            beginCapture()
        }

    @Test
    fun thePoliciesAreOrderedAndNamedAsOnTheWire() {
        assertEquals(listOf("off", "wifi", "always"), TracewayImagePolicy.entries.map { it.wire })
        assertEquals(TracewayImagePolicy.WIFI, TracewayImagePolicy.lower(TracewayImagePolicy.ALWAYS, TracewayImagePolicy.WIFI))
        assertEquals(TracewayImagePolicy.WIFI, TracewayImagePolicy.lower(TracewayImagePolicy.WIFI, TracewayImagePolicy.ALWAYS))
        assertEquals(TracewayImagePolicy.OFF, TracewayImagePolicy.lower(TracewayImagePolicy.WIFI, TracewayImagePolicy.OFF))
        assertEquals(TracewayImagePolicy.ALWAYS, TracewayImagePolicy.from("always"))
        for (bad in listOf("WIFI", "sometimes", true, 1, null)) assertNull("$bad", TracewayImagePolicy.from(bad))
    }

    @Test
    fun thePolicyDecidesByTheConnectionWhenAFrameIsCaptured() {
        assertEquals("off until the settings allow pictures", TracewayImagePolicy.OFF, WireframeImages(onReady = {}).policy)
        val images = images(TracewayImagePolicy.OFF)
        var unmetered = true
        images.unmetered = { unmetered }
        images.beginCapture()
        assertFalse(images.allowed)
        assertNull("nothing is recorded while pictures are off", images.lookup(photo(), 100f, 50f))
        assertTrue(images.all.isEmpty())

        images.policy = TracewayImagePolicy.WIFI
        images.beginCapture()
        assertTrue(images.allowed)
        // The connection changes during the frame: the frame keeps what it was captured with.
        unmetered = false
        assertTrue(images.allowed)
        images.beginCapture()
        assertFalse("cellular, a hotspot", images.allowed)

        images.policy = TracewayImagePolicy.ALWAYS
        images.beginCapture()
        assertTrue("always does not ask the connection", images.allowed)

        images.policy = TracewayImagePolicy.WIFI
        images.unmetered = { throw IllegalStateException("no connectivity service") }
        images.beginCapture()
        assertFalse("a connection that cannot be read is not Wi-Fi", images.allowed)
    }

    /** The size [lookup] copies a bitmap of [bw] x [bh] shown [wDp] x [hDp] at, or null when it records none. */
    private fun copySize(wDp: Float, hDp: Float, bw: Int, bh: Int, alpha: Boolean = false): List<Int>? {
        val images = images()
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { setHasAlpha(alpha) }
        assertNull("the first frame shows the placeholder", images.lookup(bitmap, wDp, hDp))
        return images.all.singleOrNull()?.let { listOf(it.wPx, it.hPx) }
    }

    @Test
    fun picturesAreCopiedAtTheirDisplayedSize() {
        assertEquals("one pixel per dp", listOf(100, 80), copySize(100f, 80f, 1000, 800))
        assertEquals("the longest side is at most 384", listOf(384, 288), copySize(400f, 300f, 1000, 800))
        assertEquals(listOf(192, 384), copySize(300f, 600f, 1000, 1000))
        assertEquals("a picture with transparency at most 192", listOf(192, 144), copySize(400f, 300f, 1000, 800, alpha = true))
        assertEquals(listOf(100, 80), copySize(100f, 80f, 1000, 800, alpha = true))
        assertEquals("never more pixels than the bitmap has", listOf(60, 60), copySize(100f, 100f, 60, 60))
        assertNull(copySize(0f, 100f, 60, 60))
        assertNull(copySize(100f, -1f, 60, 60))
        assertNull(copySize(Float.POSITIVE_INFINITY, 100f, 60, 60))
        assertNull(copySize(100f, Float.NaN, 60, 60))
    }

    /** CONTRACT §14.5 as corrected: a picture with transparency is never JPEG (it would replay as a black box). */
    @Test
    fun seeThroughPicturesAreAlwaysPngOfAtMost192Px() {
        val images = images(encoder = Executor { it.run() })
        fun encoded(bitmap: Bitmap, wDp: Float, hDp: Float, on: WireframeImages = images): WireframeImages.Image {
            on.lookup(bitmap, wDp, hDp)
            return on.all.last()
        }
        fun mime(image: WireframeImages.Image) = Regex("data:([^;]+);").find(image.css!!)!!.groupValues[1]
        fun decoded(image: WireframeImages.Image): Bitmap {
            val bytes = android.util.Base64.decode(Regex("base64,([^)]+)\\)").find(image.css!!)!!.groupValues[1], 0)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val seeThrough = { w: Int, h: Int -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0x80FF0000.toInt()) } }

        val small = encoded(seeThrough(96, 24), 96f, 24f)
        assertEquals(listOf(96, 24), listOf(small.wPx, small.hPx))
        assertEquals("image/png", mime(small))
        assertEquals("the picture keeps its transparency", 0x80, Color.alpha(decoded(small).getPixel(10, 10)))

        // Shown larger than 192 dp: PNG all the same, at most 192 px on its longest side.
        val large = encoded(seeThrough(200, 200), 300f, 300f)
        assertEquals(listOf(192, 192), listOf(large.wPx, large.hPx))
        assertEquals("image/png", mime(large))
        val picture = decoded(large)
        assertEquals(192, picture.width)
        assertEquals("its clear pixels stay clear, not black", 0x80, Color.alpha(picture.getPixel(100, 100)))
        assertTrue("red, not black", Color.red(picture.getPixel(100, 100)) > 0xF0)

        // An opaque photo is JPEG.
        assertEquals("image/jpeg", mime(encoded(photo(32, 32), 32f, 32f)))
        assertEquals("image/jpeg", mime(encoded(photo(400, 400), 300f, 300f)))

        // A noisy picture with transparency that is still over the limit at half its size is left out, never JPEG.
        val random = Random(3)
        val noise = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(192 * 192) { random.nextInt() }, 0, 192, 0, 0, 192, 192)
            setHasAlpha(true)
        }
        val tight = WireframeImages(onReady = {}, encoder = Executor { it.run() }, maxBytes = 2048).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        val dropped = encoded(noise, 192f, 192f, tight)
        assertTrue("left out", dropped.failed)
        assertNull(dropped.css)

        assertEquals(45, WireframeImages.JPEG_QUALITY)
        assertEquals(48 * 1024, WireframeImages.MAX_BYTES)
        assertEquals(192f, WireframeImages.ALPHA_MAX_SIDE_PX)
        assertEquals(128, WireframeImages.MAX_IMAGES)
        assertTrue(listOf(small.bytes, large.bytes).all { it in 1..WireframeImages.MAX_BYTES })
    }

    @Test
    fun aPictureTooBigIsRetriedAtHalfItsSizeAndOneThatCannotBeEncodedIsLeftOut() {
        val random = Random(7)
        val noise = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(512 * 512) { random.nextInt() or (0xFF shl 24) }, 0, 512, 0, 0, 512, 512)
            setHasAlpha(false)
        }
        // At quality 0.45 even noise fits 48 KiB at 384 px (about 39 KB); a lower limit makes it too big there, not at 192.
        fun jpegBytes(side: Int): Int {
            val scaled = Bitmap.createScaledBitmap(noise, side, side, true)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, WireframeImages.JPEG_QUALITY, out)
            return out.size()
        }
        val full = jpegBytes(384)
        val half = jpegBytes(192)
        val limit = (full + half) / 2
        assertTrue("$full $half", full <= WireframeImages.MAX_BYTES && half < limit && full > limit)
        val images = WireframeImages(onReady = {}, encoder = Executor { it.run() }, maxBytes = limit).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture()
        }
        images.lookup(noise, 400f, 400f)
        val image = images.all.single()
        assertEquals(384, image.wPx)
        assertFalse(image.failed)
        val jpeg = android.util.Base64.decode(Regex("base64,([^)]+)\\)").find(image.css!!)!!.groupValues[1], 0)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals("over $limit bytes at 384 px, so half that", 192, decoded.width)
        assertEquals(jpeg.size, image.bytes)
        assertTrue("${image.bytes}", image.bytes <= limit)

        // The app recycles its bitmap before the encoder gets to it: the encoder only reads the copy taken on the
        // main thread during the capture, never the app's bitmap (a recycled bitmap read off the main thread aborts
        // the process), so the picture is still recorded.
        val later = ArrayList<Runnable>()
        val deferred = images(encoder = Executor { later.add(it) })
        val gone = photo()
        deferred.lookup(gone, 200f, 100f)
        gone.recycle()
        later.forEach { it.run() }
        val copied = deferred.all.single()
        assertFalse(copied.failed)
        assertNotNull(copied.css)
        assertNull("a recycled bitmap is not looked at", deferred.lookup(gone, 200f, 100f))

        // A bitmap that cannot even be copied is left out, for good.
        val encoded = ArrayList<Runnable>()
        val unreadable = images(encoder = Executor { encoded.add(it) })
        val recycled = photo().apply { recycle() }
        assertNull(unreadable.lookup(recycled, 200f, 100f))
        assertTrue("nothing to copy, nothing to encode", encoded.isEmpty())
    }

    @Test
    fun theEncoderNeverTouchesTheAppsBitmapAfterLookup() {
        // Image loaders recycle and reuse pooled bitmaps on their own schedule; a recycle while another thread reads
        // the bitmap aborts the process in hwui. The encoder must only ever see the SDK's own copy.
        val queued = ArrayList<Runnable>()
        val images = images(encoder = Executor { queued.add(it) })
        val bm = photo(200, 200)
        assertNull(images.lookup(bm, 100f, 100f))
        bm.recycle()
        queued.forEach { it.run() }
        val image = images.all.single()
        assertFalse(image.failed)
        assertNotNull(image.css)
        val jpeg = android.util.Base64.decode(Regex("base64,([^)]+)\\)").find(image.css!!)!!.groupValues[1], 0)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals("copied at the size it shows", 100, decoded.width)
        val pixel = decoded.getPixel(75, 75)
        assertTrue("the app's picture, red: ${Integer.toHexString(pixel)}", Color.red(pixel) > 150 && Color.green(pixel) < 100)
    }

    @Test
    fun copiesWaitingForTheEncoderAreBounded() {
        val queued = ArrayList<Runnable>()
        val images = images(encoder = Executor { queued.add(it) })
        // Each 256 x 384 copy takes about 0.4 MB: a few wait at most, the rest wait for later frames.
        val bitmaps = List(12) { photo(400, 600) }
        for (b in bitmaps) images.lookup(b, 400f, 600f)
        val waiting = images.all.size
        assertTrue("$waiting", waiting in 1 until bitmaps.size)
        assertTrue(waiting.toLong() * 256 * 384 * 4 <= WireframeImages.MAX_PENDING_BYTES)
        // Encoded, their copies are released: the next frame copies more.
        queued.forEach { it.run() }
        queued.clear()
        images.beginCapture()
        for (b in bitmaps) images.lookup(b, 400f, 600f)
        assertTrue(images.all.size > waiting)
    }

    @Test
    fun pastTheCapPicturesNotShownForAWhileMakeRoom() {
        val images = WireframeImages(onReady = {}, maxImages = 2, encoder = Executor { it.run() }).apply {
            policy = TracewayImagePolicy.ALWAYS
        }
        val t0 = 1_000_000L
        images.beginCapture(t0)
        val a = photo(color = Color.RED)
        val b = photo(color = Color.GREEN)
        images.lookup(a, 100f, 50f)
        images.lookup(b, 100f, 50f)
        assertEquals(2, images.all.size)

        // Both still show: a third waits.
        images.beginCapture(t0 + 1000)
        assertNotNull(images.lookup(a, 100f, 50f))
        assertNotNull(images.lookup(b, 100f, 50f))
        val c = photo(color = Color.BLUE)
        assertNull(images.lookup(c, 100f, 50f))
        assertEquals(listOf("p0", "p1"), images.all.map { it.cls })

        // Neither was shown for longer than a picture lingers: the new one takes their place, with a class of its own.
        images.beginCapture(t0 + WireframeImages.LINGER_MS + 1)
        images.lookup(c, 100f, 50f)
        assertEquals(listOf("p2"), images.all.map { it.cls })
        assertNotNull(images.lookup(c, 100f, 50f))
    }

    @Test
    fun pastTheCapThePictureShownLongestAgoMakesRoomThoughItStillLingers() {
        val images = WireframeImages(onReady = {}, maxImages = 2, encoder = Executor { it.run() }).apply {
            policy = TracewayImagePolicy.ALWAYS
        }
        val t0 = 1_000_000L
        // As the capture does after each frame: what was shown, when.
        fun shown(at: Long) = images.shown.forEach { it.lastShownMs = at }
        val a = photo(color = Color.RED)
        val b = photo(color = Color.GREEN)
        val c = photo(color = Color.BLUE)
        images.beginCapture(t0)
        images.lookup(a, 100f, 50f)
        images.lookup(b, 100f, 50f)
        images.beginCapture(t0 + 1000)
        images.lookup(a, 100f, 50f)
        images.lookup(b, 100f, 50f)
        shown(t0 + 1000)
        images.beginCapture(t0 + 2000)
        assertNotNull(images.lookup(b, 100f, 50f))
        shown(t0 + 2000)
        // a was shown longest ago, 1 s: its style still lingers, but it goes, not the stop of every new picture.
        images.beginCapture(t0 + 3000)
        assertNull("the first frame of a picture shows the placeholder", images.lookup(c, 100f, 50f))
        assertEquals(listOf("p1", "p2"), images.all.map { it.cls })
        assertNotNull(images.lookup(c, 100f, 50f))
        // Shown again, a is a new picture with a new class, and b, shown longest ago now, makes room.
        images.beginCapture(t0 + 4000)
        images.lookup(c, 100f, 50f)
        shown(t0 + 4000)
        images.beginCapture(t0 + 5000)
        images.lookup(a, 100f, 50f)
        assertEquals(listOf("p2", "p3"), images.all.map { it.cls })

        // Pictures still encoding never make room: the new one waits.
        val pending = WireframeImages(onReady = {}, maxImages = 1, encoder = Executor { }).apply {
            policy = TracewayImagePolicy.ALWAYS
            beginCapture(t0)
        }
        pending.lookup(a, 100f, 50f)
        pending.beginCapture(t0 + 1000)
        assertNull(pending.lookup(b, 100f, 50f))
        assertEquals(listOf("p0"), pending.all.map { it.cls })
    }

    @Test
    fun aNewSessionRecordsItsPicturesAgain() {
        val images = WireframeImages(onReady = {}, maxImages = 1, encoder = Executor { it.run() }).apply {
            policy = TracewayImagePolicy.ALWAYS
        }
        images.beginCapture(1_000L)
        val first = photo()
        images.lookup(first, 100f, 50f)
        val other = photo(color = Color.GREEN)
        images.beginCapture(2_000L)
        assertNotNull(images.lookup(first, 100f, 50f))
        assertNull("the cap is reached by a picture on screen", images.lookup(other, 100f, 50f))
        images.newSession()
        images.beginCapture(3_000L)
        images.lookup(other, 100f, 50f)
        assertEquals(listOf("p1"), images.all.map { it.cls })
    }

    // ---- where a picture sits in its view -------------------------------------------

    /** Where [node] is inside [v], and its size: left, top, width, height in dp. */
    private fun RealScreen.placed(screen: VNode, node: VNode, v: View): List<Float> {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        val (x, y) = position(screen, node)
        return listOf(x - at[0] / density, y - at[1] / density, styleNumber(node, "width"), styleNumber(node, "height"))
    }

    private fun assertPlaced(message: String, expected: List<Float>, got: List<Float>) {
        for (k in 0 until 4) assertEquals("$message: $got", expected[k], got[k], 0.6f)
    }

    private fun row(a: Context, vararg views: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v)
    }

    /** Image views of 100 x 100 dp, each showing its bitmap with its scale type, four to a row. */
    private fun placements(policy: TracewayImagePolicy, vararg shown: Pair<Bitmap, ImageView.ScaleType>): Triple<RealScreen, VNode, List<ImageView>> {
        val views = ArrayList<ImageView>()
        val s = RealScreen.open { a ->
            for ((bitmap, scale) in shown) views.add(imageView(a, bitmap, 100, 100, scale))
            column(a, *views.chunked(4).map { row(a, *it.toTypedArray()) }.toTypedArray())
        }.policy(policy)
        return Triple(s, s.settled(), views)
    }

    private fun pictureNodes(screen: VNode): List<VNode> =
        allNodes(screen).filter { val c = it.attributes["class"] ?: ""; c == "im" || c.startsWith("im ") }.toList()

    @Test
    fun scaleTypesPlaceThePictureInItsView() {
        val scales = arrayOf(
            ImageView.ScaleType.FIT_XY, ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.CENTER_CROP,
            ImageView.ScaleType.CENTER, ImageView.ScaleType.FIT_START, ImageView.ScaleType.FIT_END,
        )
        // A 50 x 20 picture in 100 x 100 views, as placeholders.
        val (s, screen, views) = placements(TracewayImagePolicy.OFF, *scales.map { photo(50, 20) to it }.toTypedArray())
        val nodes = pictureNodes(screen)
        assertEquals(scales.size, nodes.size)
        val expected = listOf(
            listOf(0f, 0f, 100f, 100f),
            listOf(0f, 30f, 100f, 40f),
            // Cropped: as large as the view, the picture's middle.
            listOf(0f, 0f, 100f, 100f),
            listOf(25f, 40f, 50f, 20f),
            listOf(0f, 0f, 100f, 40f),
            listOf(0f, 60f, 100f, 40f),
        )
        for (i in scales.indices) assertPlaced("${scales[i]}", expected[i], s.placed(screen, nodes[i], views[i]))
    }

    @Test
    fun theRemainingScaleTypesAPictureLargerThanItsViewAndOneWithoutASize() {
        lateinit var plain: ImageView
        val views = ArrayList<ImageView>()
        val s = RealScreen.open { a ->
            views.add(imageView(a, photo(50, 20), 100, 100, ImageView.ScaleType.CENTER_INSIDE))
            views.add(imageView(a, photo(50, 20), 100, 100, ImageView.ScaleType.MATRIX))
            views.add(imageView(a, photo(300, 60), 100, 100, ImageView.ScaleType.CENTER_INSIDE))
            views.add(imageView(a, photo(300, 60), 100, 100, ImageView.ScaleType.CENTER))
            plain = ImageView(a).apply {
                setImageDrawable(android.graphics.drawable.ColorDrawable(Color.MAGENTA))
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(100, 100)
            }
            column(a, row(a, *views.toTypedArray()), plain)
        }.policy(TracewayImagePolicy.OFF)
        val screen = s.settled()
        val nodes = pictureNodes(screen)
        assertEquals(4, nodes.size)
        assertPlaced("not enlarged", listOf(25f, 40f, 50f, 20f), s.placed(screen, nodes[0], views[0]))
        assertPlaced("at its own size, from the corner", listOf(0f, 0f, 50f, 20f), s.placed(screen, nodes[1], views[1]))
        // Larger than the view: CENTER_INSIDE shrinks it to fit; CENTER overflows it and the view cuts it.
        assertPlaced("shrunk", listOf(0f, 40f, 100f, 20f), s.placed(screen, nodes[2], views[2]))
        assertPlaced("cut", listOf(0f, 20f, 100f, 60f), s.placed(screen, nodes[3], views[3]))
        // A drawable without a size covers the view, whatever the scale type.
        val fill = allNodes(screen).single { style(it).contains("background-color:#ff00ff") }
        assertPlaced("covers", listOf(0f, 0f, 100f, 100f), s.placed(screen, fill, plain))
    }

    @Test
    fun aRecordedPictureIsSizedAndOffsetAsItsScaleTypePlacesIt() {
        val scales = arrayOf(
            ImageView.ScaleType.FIT_CENTER, ImageView.ScaleType.CENTER_CROP, ImageView.ScaleType.CENTER, ImageView.ScaleType.FIT_END,
        )
        val (s, screen, views) = placements(TracewayImagePolicy.ALWAYS, *scales.map { photo(150, 60) to it }.toTypedArray())
        val nodes = pictureNodes(screen)
        assertEquals(listOf("im p0", "im p1", "im p2", "im p3"), nodes.map { it.attributes["class"] })
        // Fitted: the node is the picture.
        assertPlaced("FIT_CENTER", listOf(0f, 30f, 100f, 40f), s.placed(screen, nodes[0], views[0]))
        assertTrue(style(nodes[0]), style(nodes[0]).contains("background-size:100px 40px;background-position:0px 0px;"))
        // Cropped and overflowing: the node is the view, and the picture is larger and shifted in it.
        assertPlaced("CENTER_CROP", listOf(0f, 0f, 100f, 100f), s.placed(screen, nodes[1], views[1]))
        assertTrue(style(nodes[1]), style(nodes[1]).contains("background-size:250px 100px;background-position:-75px 0px;"))
        assertPlaced("CENTER", listOf(0f, 20f, 100f, 60f), s.placed(screen, nodes[2], views[2]))
        assertTrue(style(nodes[2]), style(nodes[2]).contains("background-size:150px 60px;background-position:-25px 0px;"))
        assertPlaced("FIT_END", listOf(0f, 60f, 100f, 40f), s.placed(screen, nodes[3], views[3]))
        assertTrue(style(nodes[3]), style(nodes[3]).contains("background-size:100px 40px;background-position:0px 0px;"))
    }
}
