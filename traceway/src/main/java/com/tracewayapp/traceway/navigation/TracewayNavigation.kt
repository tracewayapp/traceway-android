package com.tracewayapp.traceway.navigation

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.guarded
import java.util.WeakHashMap

/**
 * Reports every screen [navController] shows as a navigation, with its
 * path (`/orders/8841`) and its route template (`/orders/:id`), so issues,
 * screen metrics and navigation trigger steps see the screens of a
 * Compose Navigation (or any `NavController`) app. Call it once per
 * controller, where it is made; more calls do nothing.
 *
 * ```kotlin
 * val nav = rememberNavController()
 * LaunchedEffect(nav) { Traceway.trackNavigation(nav) }
 * ```
 *
 * Java: `TracewayNavigation.track(navController)`. Destinations without a
 * route (an XML graph of fragments) are left to the automatic fragment
 * screens.
 */
fun Traceway.trackNavigation(navController: NavController?) = TracewayNavigation.track(navController)

/** [trackNavigation] for Java: `TracewayNavigation.track(navController)`. */
object TracewayNavigation {
    /** The back stack entry ids each tracked controller has shown, oldest first: a pop goes back to one of them. */
    private val tracked = WeakHashMap<NavController, ArrayList<String>>()

    @JvmStatic
    fun track(navController: NavController?) {
        if (navController == null) return
        guarded("trackNavigation") {
            synchronized(tracked) {
                if (tracked.containsKey(navController)) return
                tracked[navController] = ArrayList()
            }
            navController.addOnDestinationChangedListener(Listener)
        }
    }

    private object Listener : NavController.OnDestinationChangedListener {
        override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
            guarded("navigation destination") {
                val screen = screenOf(destination.route, arguments) ?: return
                val entry = guarded("navigation entry", null) { controller.currentBackStackEntry?.id }
                Traceway.recordNavigation(screen.path, actionFor(controller, entry), null, screen.route)
            }
        }
    }

    /** `pop` when [entry] was shown before (the user went back to it), else `push`. */
    private fun actionFor(controller: NavController, entry: String?): String {
        if (entry == null) return "push"
        synchronized(tracked) {
            val seen = tracked[controller] ?: return "push"
            val at = seen.indexOf(entry)
            if (at >= 0) {
                while (seen.size > at + 1) seen.removeAt(seen.size - 1)
                return "pop"
            }
            seen.add(entry)
            if (seen.size > MAX_ENTRIES) seen.removeAt(0)
            return "push"
        }
    }

    private const val MAX_ENTRIES = 64

    /** A destination as a screen: the path the user is on and the route template it is an instance of. */
    internal class Screen(val path: String, val route: String)

    private val ARGUMENT = Regex("\\{([^}/]+)\\}")
    private val QUALIFIED_CLASS = Regex("^(?:[A-Za-z_][\\w$]*\\.)+([A-Z][\\w$]*)$")

    /**
     * The screen of a destination whose route is [route] (`orders/{id}`,
     * `search?query={q}`, or a type-safe route's
     * `com.example.app.OrderDetail/{id}`), shown with [arguments]: the
     * route's path with each `{name}` replaced by the argument of that name,
     * whatever its type (`/orders/8841`), and the template with `:name`
     * (`/orders/:id`). A type-safe route's class is named by its simple
     * name (`/OrderDetail/8841`); query arguments are left out. Null without
     * a route.
     */
    internal fun screenOf(route: String?, arguments: Bundle?): Screen? {
        val pattern = route?.substringBefore('?')?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
        val segments = pattern.split('/').toMutableList()
        QUALIFIED_CLASS.matchEntire(segments[0])?.let { segments[0] = it.groupValues[1] }
        val template = segments.joinToString("/")
        val path = ARGUMENT.replace(template) { m ->
            val value = guarded("navigation argument", null) {
                @Suppress("DEPRECATION")
                arguments?.get(m.groupValues[1])?.toString()
            }
            value?.takeIf { it.isNotEmpty() } ?: ":${m.groupValues[1]}"
        }
        return Screen("/$path", "/" + ARGUMENT.replace(template) { m -> ":${m.groupValues[1]}" })
    }
}
