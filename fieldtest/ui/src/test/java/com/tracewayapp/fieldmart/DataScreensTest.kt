package com.tracewayapp.fieldmart

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/** The app's test build pointed at a server that answers with the fixture responses of fx-snapshot.mjs. */
class DataTestApp : FieldmartApplication() {
    override fun createTelemetry(): Telemetry = RecordingTelemetry
    override fun inMainProcess() = true
    override fun readSettings(): LaunchSettings {
        val s = LaunchSettings.read(this)
        return LaunchSettings(s.run, s.variant, s.scenario, s.baseUrl, DataScreensTest.base, s.token, s.evidence, s.fps, s.toolkit, s.remote, s.target, s.campaign)
    }
}

/**
 * The screens with the campaign's real data (shared fixtures through the vendored generator) and labels: what the
 * journey's checkpoints expect to see is there, with the ids the driver taps. Compose texts are read from the
 * semantics tree. Skipped until `node fieldtest/harness/fx-snapshot.mjs` has written the responses.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = DataTestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DataScreensTest {
    companion object {
        private var server: MockWebServer? = null
        var base: String = "http://127.0.0.1:9/fx"
        private val dir = File(System.getProperty("fm.snapshot") ?: "")

        @BeforeClass
        @JvmStatic
        fun start() {
            if (!dir.isDirectory) return
            val s = MockWebServer()
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    if (path.startsWith("/fx/api/quotes")) return MockResponse().setBody("{\"n\":1,\"count\":300}")
                    if (path.startsWith("/fx/api/checkout")) return MockResponse().setBody("{\"order\":\"FM-100001\"}")
                    val f = File(dir, path.replace(Regex("[^A-Za-z0-9]+"), "_") + ".json")
                    return if (f.isFile) MockResponse().setHeader("Content-Type", "application/json").setBody(f.readText()) else MockResponse().setResponseCode(404)
                }
            }
            s.start()
            server = s
            base = s.url("/fx").toString().trimEnd('/')
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            server?.shutdown()
        }
    }

    /** Lets the main looper run while the data comes in on other threads. */
    private fun settle(ms: Long = 1500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))
            Thread.sleep(15)
        }
    }

    private fun idleFrames(n: Int) {
        repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)) }
    }

    private fun viewTexts(root: View): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is TextView && !v.text.isNullOrEmpty()) out += v.text.toString()
            if (v is TextView && !v.hint.isNullOrEmpty()) out += v.hint.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun names(root: View): Set<String> {
        val out = HashSet<String>()
        fun walk(v: View) {
            if (v.id != View.NO_ID) runCatching { out += v.resources.getResourceEntryName(v.id) }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** (testTag, text) of every Compose semantics node under [root]. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun compose(root: View, forceLayout: Boolean = true): List<Pair<String?, String?>> {
        val out = ArrayList<Pair<String?, String?>>()
        fun node(n: SemanticsNode) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text
            if (tag != null || text != null) out += tag to text
            n.children.forEach(::node)
        }
        fun walk(v: View) {
            if (v is ViewRootForTest) {
                // Off for Markets: the device does not force a layout either, and a stale screen must show here.
                if (forceLayout) v.measureAndLayoutForTest()
                node(v.semanticsOwner.unmergedRootSemanticsNode)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun dump(root: View, name: String) {
        val out = StringBuilder()
        fun tree(n: SemanticsNode, d: Int) {
            out.append("  ".repeat(d)).append(n.config.getOrNull(SemanticsProperties.TestTag) ?: "-").append(" '")
                .append(n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "").append("' ").append(n.boundsInRoot)
                .append('\n')
            if (d < 16) n.children.forEach { tree(it, d + 1) }
        }
        fun walk(v: View) {
            if (v is ViewRootForTest) {
                out.append("== ").append(v.javaClass.simpleName).append(' ').append(v.view.width).append('x').append(v.view.height).append('\n')
                v.measureAndLayoutForTest()
                tree(v.semanticsOwner.unmergedRootSemanticsNode, 0)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        File(dir.parentFile, "$name-semantics.txt").writeText(out.toString())
    }

    private fun assertShows(where: String, have: Collection<String>, vararg want: String) {
        for (w in want) assertTrue("$where shows \"$w\"; it has $have", have.any { it.contains(w) })
    }

    @Test
    fun screensShowTheJourneysTexts() {
        assumeTrue("no fx-snapshot yet", server != null)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.window.decorView
        // The data the Markets chart draws: 5,000 candles of the first symbol.
        val api = FieldmartApplication.of(activity).api
        val candles = runCatching { api.candles("HEAM", "1H") }
        assertTrue("candles: ${candles.exceptionOrNull()} ${candles.getOrNull()?.size} ${candles.getOrNull()?.lastOrNull()?.let { "${it.t} ${it.c}" }}",
            candles.getOrNull()?.size == 5000 && candles.getOrNull()!!.last().c > 0)
        settle(2500)
        // s0-home: "Fieldmart", "Search Fieldmart", "Outdoor", "Today's deals"; the grid, product 17 on the first page.
        assertShows("Home", viewTexts(root), "Fieldmart", "Search Fieldmart", "Outdoor", "Today's deals", "Popular now", "Home", "Markets")
        val home = names(root)
        assertTrue(home.toString(), "fm_home_chip_0" in home && "fm_home_deal_0" in home && "fm_home_product_1" in home)
        assertShows("Home deals", viewTexts(root), "Cedar Oak Desk Lamp", "-25%")

        // s1-category: chip 3 then chip 7 shows "Lighting" and its first product "Lumen Wall Sconce".
        root.findViewById<View>(activity.resources.getIdentifier("fm_home_chip_3", "id", activity.packageName)).performClick()
        settle()
        root.findViewById<View>(activity.resources.getIdentifier("fm_home_chip_7", "id", activity.packageName)).performClick()
        settle()
        assertShows("Category", viewTexts(root), "Lighting", "Lumen Wall Sconce")

        // s2-product.
        activity.push(MainActivity.Screen("product", 17))
        settle(2500)
        val product = viewTexts(root)
        assertShows("Product", product, "Cedar Oak Desk Lamp", "Vellowmark", "$104.00", "Add to cart", "1 / 8", "Qty 1", "Read more", "Size", "Colour")
        val pnames = names(root)
        for (id in listOf("fm_product_size_2", "fm_product_colour_5", "fm_product_qty_plus", "fm_product_readmore")) assertTrue("$id: $pnames", id in pnames)
        activity.back()
        settle()

        // Search (Compose): "trail" gives "Trail Cork Pet Bowl", "Trail Weekender".
        activity.push(MainActivity.Screen("search"))
        settle()
        val search = compose(root)
        assertShows("Search", search.mapNotNull { it.second }, "Search", "Search products")
        activity.back()
        settle()

        // Markets (Compose): the symbol, the timeframes, the watchlist, polls.
        activity.select(MainActivity.Tab.MARKETS)
        // The first layout happens before the data is there, as on a device (its first frame comes at once).
        idleFrames(3)
        compose(root)
        settle(2500)
        val markets = compose(root)
        val mtexts = markets.mapNotNull { it.second }
        assertShows("Markets", mtexts, "HEAM", "1H", "1D", "1W", "Watchlist", "Order book", "Polls")
        // The chart's axis labels are prices of the loaded candles (not the empty chart's 0.00 and 0.01).
        val prices = mtexts.filter { Regex("^\\d{2,}\\.\\d{2}$").matches(it) }
        assertTrue("chart price labels: $prices in $mtexts", prices.size >= 4 && "0.01" !in mtexts)
        val mtags = markets.mapNotNull { it.first }

        for (t in listOf("fm-markets-chart", "fm-markets-price", "fm-markets-tf-1M", "fm-markets-watchlist", "fm-markets-book-open", "fm-markets-row-HEAM")) {
            assertTrue("$t in $mtags; texts ${mtexts.take(60)}", t in mtags)
        }

        // Feed (Compose): "Activity", "Week 1", the first rows' labels, the controls.
        activity.select(MainActivity.Tab.FEED)
        settle(2500)
        dump(root, "feed")
        settle(3000)
        dump(root, "feed-later")
        val feed = compose(root)
        assertShows("Feed", feed.mapNotNull { it.second }, "Activity", "Week 1", "HARS moved up 0.18 %", "Payment received for FM-977457", "Jump to end", "Jump to start", "Refresh")
        val ftags = feed.mapNotNull { it.first }
        for (t in listOf("fm-feed-list", "fm-feed-row-0", "fm-feed-row-1", "fm-feed-jump-end", "fm-feed-goto-field", "fm-feed-goto-go", "fm-feed-refresh")) {
            assertTrue("$t in ${ftags.take(30)}", t in ftags)
        }

        // More → Diagnostics (Compose).
        activity.select(MainActivity.Tab.MORE)
        settle()
        assertTrue("fm-more-diag" in compose(root).mapNotNull { it.first })
        activity.push(MainActivity.Screen("diag"))
        settle()
        val diag = compose(root)
        assertShows("Diagnostics", diag.mapNotNull { it.second }, "Diagnostics", "Run", "Report handled error", "Write 1,000 log lines", "Send message")
        for (t in listOf("fm-diag-handled", "fm-diag-unhandled", "fm-diag-hang", "fm-diag-logs", "fm-diag-http500", "fm-diag-slow", "fm-diag-event", "fm-diag-message", "fm-diag-run", "fm-diag-session", "fm-nav-back")) {
            assertTrue(t, t in diag.mapNotNull { it.first })
        }

        // Checkout (Views): titles, sections, the fixed texts of the form.
        activity.select(MainActivity.Tab.CHECKOUT)
        settle()
        // "Password" shows once "Create account" is on, as in the journey.
        assertShows("Checkout", viewTexts(root), "Checkout", "Contact", "Delivery", "Payment", "Review", "Full name", "Email", "Create account",
            "Morning", "Afternoon", "Evening", "Leave at door", "Gift wrap", "Card number", "CVV", "Name on card", "Save card", "Tip", "0%",
            "Promo code", "Order summary", "Loyalty number", "Terms and conditions", "Delivery instructions", "Edit items", "How was checkout?",
            "Notes", "Place order", "Cedar Oak Desk Lamp")
        controller.pause().stop().destroy()
    }
}
