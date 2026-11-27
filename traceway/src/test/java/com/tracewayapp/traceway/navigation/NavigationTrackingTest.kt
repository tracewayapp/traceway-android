package com.tracewayapp.traceway.navigation

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayClient
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.TracewayTestOverrides
import com.tracewayapp.traceway.compose.tracewayScreen
import com.tracewayapp.traceway.internal.ManualScheduler
import com.tracewayapp.traceway.otel.FakeHttp
import com.tracewayapp.traceway.wireframe.RealScreen
import com.tracewayapp.traceway.wireframe.recomposingOnTestFrames
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AND2-6: the screens of a Compose app without hand-written glue:
 * `Traceway.trackNavigation(nav)` for a `NavController`, and
 * `Modifier.tracewayScreen` for a screen no controller shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTrackingTest {
    private class Logs : LogRecordExporter {
        val items = java.util.Collections.synchronizedList(ArrayList<LogRecordData>())
        override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
            items.addAll(logs)
            return CompletableResultCode.ofSuccess()
        }
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private val logs = Logs()

    private fun start(): TracewayClient = TracewayClient.initializeForTest(
        "tok@https://traceway.test",
        TracewayOptions(recordAllSessions = true, remoteConfig = false),
        TracewayTestOverrides(logExporter = logs, http = FakeHttp(), scheduler = ManualScheduler()),
    )

    @After
    fun tearDown() = TracewayClient.resetForTest()

    private fun LogRecordData.attr(key: String): Any? {
        var found: Any? = null
        attributes.forEach { k, v -> if (k.key == key) found = v }
        return found
    }

    /** `path action route` of each navigation record so far. */
    private fun navigations(client: TracewayClient): List<String> {
        client.flush(2000)
        return synchronized(logs.items) { ArrayList(logs.items) }.filter { it.eventName == "browser.navigation" }.map {
            "${it.attr("url.full")} ${it.attr("navigation.action")} ${it.attr("app.screen.route")}"
        }
    }

    private fun bundle(vararg pairs: Pair<String, Any>) = Bundle().apply {
        for ((k, v) in pairs) {
            when (v) {
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                else -> putString(k, v.toString())
            }
        }
    }

    @Test
    fun aDestinationIsItsPathAndItsRouteTemplate() {
        fun screen(route: String?, args: Bundle?) = TracewayNavigation.screenOf(route, args)?.let { "${it.path} ${it.route}" }
        assertEquals("/orders/8841 /orders/:id", screen("orders/{id}", bundle("id" to 8841L)))
        assertEquals("an Int argument", "/users/7/posts/12 /users/:user/posts/:post", screen("users/{user}/posts/{post}", bundle("user" to 7, "post" to 12)))
        assertEquals("a type-safe route by its simple name", "/OrderDetail/8841 /OrderDetail/:id", screen("com.example.app.OrderDetail/{id}", bundle("id" to 8841L)))
        assertEquals("/Settings /Settings", screen("com.example.app.Screen.Settings", null))
        assertEquals("query arguments are left out", "/search /search", screen("search?query={q}", bundle("q" to "shoes")))
        assertEquals("a missing argument keeps its name", "/orders/:id /orders/:id", screen("orders/{id}", null))
        assertEquals("/orders.v2 /orders.v2", screen("orders.v2", null))
        assertNull("no route: left to the fragment screens", screen(null, null))
        assertNull(screen("", null))
    }

    @Test
    fun aTrackedNavControllerReportsEveryScreenOnce() {
        val client = start()
        lateinit var nav: NavHostController
        RealScreen.open { a ->
            ComposeView(a).recomposingOnTestFrames().apply {
                setContent {
                    nav = rememberNavController()
                    LaunchedEffect(nav) { Traceway.trackNavigation(nav) }
                    NavHost(nav, startDestination = "orders") {
                        composable("orders") { Text("Orders") }
                        composable("orders/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { Text("Order") }
                        composable("search?query={q}") { Text("Search") }
                    }
                }
            }
        }.idle()
        // A second call, as a recomposition makes, adds no second listener.
        Traceway.trackNavigation(nav)
        TracewayNavigation.track(nav)
        nav.navigate("orders/8841")
        nav.navigate("search?query=shoes")
        nav.popBackStack()
        assertEquals(
            listOf(
                "/orders push /orders",
                "/orders/8841 push /orders/:id",
                "/search push /search",
                "/orders/8841 pop /orders/:id",
            ),
            navigations(client),
        )
    }

    @Test
    fun aScreenModifierReportsItsScreenWhenItAppears() {
        val client = start()
        var id by mutableStateOf("8841")
        var shown by mutableStateOf(true)
        val s = RealScreen.open { a ->
            ComposeView(a).recomposingOnTestFrames().apply {
                setContent {
                    if (shown) Box(Modifier.tracewayScreen("/orders/$id", route = "/orders/:id")) { Text("Order $id") }
                }
            }
        }
        s.idle()
        s.idle()
        assertEquals(listOf("/orders/8841 push /orders/:id"), navigations(client))
        id = "8842"
        s.idle()
        shown = false
        s.idle()
        shown = true
        s.idle()
        assertEquals(
            "a new path, and the screen again when it comes back; not on every recomposition",
            listOf("/orders/8841 push /orders/:id", "/orders/8842 push /orders/:id", "/orders/8842 push /orders/:id"),
            navigations(client),
        )
    }
}
