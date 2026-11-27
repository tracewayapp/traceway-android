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
import com.tracewayapp.traceway.tracewayPrivacyMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures in the wireframe: what image views show, recorded as the replay
 * image policy, the connection, privacy masks and the session's budget
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
        // Copied at 1.5 px per dp of where it shows.
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(300, decoded.width)
        assertEquals(150, decoded.height)

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
                    tracewayPrivacyMask = TracewayMaskMode.blur()
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
}
