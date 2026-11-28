package com.tracewayapp.fieldmart

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.Interceptor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/** The app's test build: a telemetry that records what the app calls, the main process always. */
class TestApp : FieldmartApplication() {
    override fun createTelemetry(): Telemetry = RecordingTelemetry
    override fun inMainProcess() = true
}

object RecordingTelemetry : Telemetry {
    val calls = ArrayList<String>()
    override val name = "test"
    override val sessionId: String? = null
    override fun start(app: Application, settings: LaunchSettings) { calls += "start ${settings.variant}" }
    override fun interceptors(): List<Interceptor> = emptyList()
    override fun trackNavigation(nav: NavController) { calls += "trackNavigation" }
    override fun mask(view: View, blank: Boolean) { calls += "mask ${view.resources.getResourceEntryName(view.id)} blank=$blank" }
    override fun captureException(error: Throwable, attributes: Map<String, String>) { calls += "exception ${error.message}" }
    override fun captureMessage(message: String) { calls += "message $message" }
    override fun track(event: String, attributes: Map<String, Any>) { calls += "track $event" }
    override fun metric(name: String, value: Double, dimensions: Map<String, Any>) { calls += "metric $name" }
    override fun reportFullyDisplayed() { calls += "fully" }
}

/**
 * Every screen of Fieldmart under Robolectric: the activity starts, each tab and pushed screen shows without a crash,
 * and the Views elements the driver taps carry their resource ids (02-APP-SPEC.md §4). The data API is not there
 * (connections fail), so the screens show their empty state; the ids that come from data are checked on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ScreensSmokeTest {
    private fun idle(ms: Long = 200) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))
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

    @Test
    fun everyScreenShows() {
        ShadowLog.clear()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        idle(500)
        val root = activity.window.decorView
        val home = names(root)
        for (id in listOf("fm_home_grid", "fm_home_logo", "fm_home_search", "fm_home_cart", "fm_nav_home", "fm_nav_markets",
            "fm_nav_feed", "fm_nav_checkout", "fm_nav_more")) assertTrue("$id on Home: $home", id in home)
        // FM_READY comes from the first real draw pass, which Robolectric does not run: checked on the device.

        for (tab in listOf(MainActivity.Tab.MARKETS, MainActivity.Tab.FEED, MainActivity.Tab.MORE, MainActivity.Tab.CHECKOUT)) {
            activity.select(tab)
            idle(1500)
        }
        val checkout = names(root)
        for (id in listOf("form", "name", "email", "phone", "account", "address", "city", "postcode", "country", "date",
            "window_0", "window_1", "window_2", "door", "gift", "card", "expiry", "cvv", "cardname", "savecard", "tip", "promo",
            "terms", "menu", "edit", "rating_1", "rating_5", "notes", "place", "loyalty")) {
            assertTrue("fm_checkout_$id on Checkout", "fm_checkout_$id" in checkout)
        }
        assertTrue(RecordingTelemetry.calls.toString(), "mask fm_checkout_card blank=false" in RecordingTelemetry.calls)
        assertTrue("mask fm_checkout_cvv blank=true" in RecordingTelemetry.calls)
        assertNotNull(root.findViewWithTag<View>("traceway-mask"))

        activity.select(MainActivity.Tab.HOME)
        idle()
        activity.push(MainActivity.Screen("product", 17))
        idle(800)
        val product = names(root)
        for (id in listOf("fm_product_gallery", "fm_product_reviews", "fm_product_add", "fm_nav_back")) assertTrue("$id on Product", id in product)
        activity.back()
        idle()
        activity.push(MainActivity.Screen("search"))
        idle(800)
        activity.back()
        idle()
        activity.select(MainActivity.Tab.MORE)
        activity.push(MainActivity.Screen("diag"))
        idle(800)
        activity.back()
        idle()
        val screens = ShadowLog.getLogs().filter { it.tag == "FM" && it.msg.startsWith("FM_SCREEN ") }.map { it.msg.split(' ')[1] }
        assertTrue(screens.toString(), screens.containsAll(listOf("home", "markets", "feed", "more", "checkout", "product", "search", "diag")))
        assertTrue(ShadowLog.getLogs().count { it.tag == "Fieldmart" && it.msg.startsWith("FM log ") } >= screens.size)
        controller.pause().stop().destroy()
    }
}
