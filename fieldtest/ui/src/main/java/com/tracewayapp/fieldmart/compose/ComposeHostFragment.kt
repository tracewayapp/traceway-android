package com.tracewayapp.fieldmart.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.Fragment
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.MainActivity

val LocalApp = staticCompositionLocalOf<FieldmartApplication> { error("no app") }
val LocalMain = staticCompositionLocalOf<MainActivity> { error("no activity") }

private val Brand = Color(0xFF0B6E4F)

@Composable
fun FieldmartTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Brand, onPrimary = Color.White, secondary = Color(0xFFF2A541)),
        content = content,
    )
}

/**
 * Hosts every Compose screen in one Navigation Compose graph (markets, feed, search, more, diag); the activity's
 * navigator says which destination shows. The destinations are reported with `Traceway.trackNavigation` through
 * the Telemetry seam.
 */
class ComposeHostFragment : Fragment() {
    private var nav: NavHostController? = null
    private var pending: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val start = requireArguments().getString("start") ?: "markets"
        val app = FieldmartApplication.of(requireContext())
        val main = requireActivity() as MainActivity
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FieldmartTheme {
                    CompositionLocalProvider(LocalApp provides app, LocalMain provides main) {
                        Host(start)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Host(start: String) {
        val controller = rememberNavController()
        val app = LocalApp.current
        LaunchedEffect(controller) {
            nav = controller
            app.telemetry.trackNavigation(controller)
            pending?.let { pending = null; go(controller, it) }
        }
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            NavHost(controller, startDestination = start) {
                composable("markets") { MarketsScreen() }
                composable("feed") { FeedScreen() }
                composable("search") { SearchScreen() }
                composable("more") { MoreScreen() }
                composable("diag") { DiagnosticsScreen() }
            }
        }
    }

    /** Shows the destination of [screen]; before the graph exists, it becomes the one shown first. */
    fun navigate(screen: MainActivity.Screen) {
        val c = nav
        if (c == null) pending = screen.name else go(c, screen.name)
    }

    private fun go(c: NavHostController, route: String) {
        if (c.currentDestination?.route == route) return
        c.navigate(route) {
            popUpTo(c.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    companion object {
        fun of(start: String, arg: Int) = ComposeHostFragment().apply {
            arguments = Bundle().apply {
                putString("start", start)
                putInt("arg", arg)
            }
        }
    }
}
