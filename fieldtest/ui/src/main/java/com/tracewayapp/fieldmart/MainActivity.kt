package com.tracewayapp.fieldmart

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tracewayapp.fieldmart.checkout.CheckoutFragment
import com.tracewayapp.fieldmart.compose.ComposeHostFragment
import com.tracewayapp.fieldmart.feed.FeedViewsFragment
import com.tracewayapp.fieldmart.home.HomeFragment
import com.tracewayapp.fieldmart.product.ProductFragment
import com.tracewayapp.fieldmart.ui.R

/**
 * The one activity: a bottom navigation over five tabs, each with its own stack of screens. View screens (Home,
 * Product, Checkout, the Views feed) are fragments; Compose screens (Markets, Feed, Search, More, Diagnostics) are
 * destinations of one Navigation Compose host. Tabs keep their fragments (hidden, lifecycle held at STARTED), so the
 * checkout form keeps what was typed while the user is elsewhere, as a real shop app does.
 */
class MainActivity : AppCompatActivity() {
    enum class Tab(val menuId: Int, val root: String) {
        HOME(R.id.fm_nav_home, "home"),
        MARKETS(R.id.fm_nav_markets, "markets"),
        FEED(R.id.fm_nav_feed, "feed"),
        CHECKOUT(R.id.fm_nav_checkout, "checkout"),
        MORE(R.id.fm_nav_more, "more"),
    }

    data class Screen(val name: String, val arg: Int = 0)

    private val stacks = Tab.entries.associateWith { ArrayDeque(listOf(Screen(it.root))) }
    private var tab = Tab.HOME
    private lateinit var nav: BottomNavigationView
    private var syncingNav = false
    private val app by lazy { FieldmartApplication.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A process restored by Android starts over at Home: the journey always launches cold.
        super.onCreate(null)
        setContentView(R.layout.fm_activity_main)
        Probe.attach(this)
        nav = findViewById(R.id.fm_nav)
        nav.setOnItemSelectedListener { item ->
            if (!syncingNav) Tab.entries.firstOrNull { it.menuId == item.itemId }?.let { select(it) }
            true
        }
        nav.setOnItemReselectedListener { item ->
            Tab.entries.firstOrNull { it.menuId == item.itemId }?.let { popToRoot(it) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val first = window.decorView
        first.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                // After the first frame's draw pass: FM_READY, the T0 of every scenario.
                Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                    Markers.ready()
                    first.viewTreeObserver.removeOnDrawListener(this)
                    app.telemetry.sessionId?.let { Markers.session(it) }
                }
            }
        })
        show(Screen("home"))
    }

    override fun onStop() {
        super.onStop()
        Probe.save()
    }

    /** Opens [screen] on top of the current tab's stack. */
    fun push(screen: Screen) {
        stacks.getValue(tab).addLast(screen)
        show(screen)
    }

    fun back() {
        val stack = stacks.getValue(tab)
        when {
            stack.size > 1 -> {
                val gone = stack.removeLast()
                if (gone.name == "product") removeProduct()
                show(stack.last())
            }
            tab != Tab.HOME -> select(Tab.HOME)
            else -> finish()
        }
    }

    fun select(target: Tab) {
        tab = target
        syncNav()
        show(stacks.getValue(target).last())
    }

    private fun popToRoot(target: Tab) {
        val stack = stacks.getValue(target)
        while (stack.size > 1) if (stack.removeLast().name == "product") removeProduct()
        select(target)
        (current as? Scrollable)?.scrollToTop()
    }

    private fun syncNav() {
        if (nav.selectedItemId == tab.menuId) return
        syncingNav = true
        nav.selectedItemId = tab.menuId
        syncingNav = false
    }

    private var current: Fragment? = null

    private fun show(screen: Screen) {
        val fm = supportFragmentManager
        val target: Fragment = when (screen.name) {
            "home" -> fm.findFragmentByTag("home") ?: HomeFragment()
            "product" -> (fm.findFragmentByTag("product") as? ProductFragment)?.takeIf { it.productId == screen.arg }
                ?: ProductFragment.of(screen.arg)
            "checkout" -> fm.findFragmentByTag("checkout") ?: CheckoutFragment()
            "feed" -> if (app.settings.feedViews) fm.findFragmentByTag("feedviews") ?: FeedViewsFragment() else compose(screen)
            else -> compose(screen)
        }
        val tag = when (target) {
            is HomeFragment -> "home"
            is ProductFragment -> "product"
            is CheckoutFragment -> "checkout"
            is FeedViewsFragment -> "feedviews"
            else -> "compose"
        }
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        fm.findFragmentByTag("product")?.takeIf { it !== target && tag == "product" }?.let { tx.remove(it) }
        for (f in fm.fragments) {
            if (f === target || f.tag == null) continue
            if (f.tag == "product" && tag == "product") continue
            if (!f.isHidden) tx.hide(f)
            if (f.isAdded) tx.setMaxLifecycle(f, Lifecycle.State.STARTED)
        }
        if (!target.isAdded) tx.add(R.id.fm_content, target, tag) else tx.show(target)
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.commitNow()
        current = target
        if (target is ComposeHostFragment) target.navigate(screen)
        Markers.screen(screen.name)
    }

    private fun compose(screen: Screen): Fragment =
        supportFragmentManager.findFragmentByTag("compose") ?: ComposeHostFragment.of(screen.name, screen.arg)

    private fun removeProduct() {
        supportFragmentManager.findFragmentByTag("product")?.let {
            supportFragmentManager.beginTransaction().remove(it).commitNow()
        }
    }

    /** A screen whose tab button, pressed again, scrolls it back to the top. */
    interface Scrollable {
        fun scrollToTop()
    }
}
