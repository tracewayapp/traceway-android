package com.tracewayapp.traceway.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.tracewayapp.traceway.Traceway

/**
 * Names the screen this composable shows: when it appears, Traceway
 * records a navigation to [path] (`/orders/8841`), with [route], the route
 * template the path is an instance of (`/orders/:id`), as
 * [Traceway.recordNavigation] does. For screens that no `NavController`
 * shows (Compose Navigation apps call `Traceway.trackNavigation(nav)`
 * instead): put it on the screen's root.
 *
 * ```kotlin
 * Column(Modifier.fillMaxSize().tracewayScreen("/orders/$id", route = "/orders/:id")) { ... }
 * ```
 *
 * A screen that recomposes is not recorded again; one that leaves and
 * comes back is, and so is a new [path].
 */
fun Modifier.tracewayScreen(path: String, route: String? = null): Modifier = this then TracewayScreenElement(path, route)

private data class TracewayScreenElement(val path: String, val route: String?) : ModifierNodeElement<TracewayScreenNode>() {
    override fun create(): TracewayScreenNode = TracewayScreenNode(path, route)

    override fun update(node: TracewayScreenNode) = node.update(path, route)

    override fun InspectorInfo.inspectableProperties() {
        name = "tracewayScreen"
        properties["path"] = path
        properties["route"] = route
    }
}

private class TracewayScreenNode(private var path: String, private var route: String?) : Modifier.Node() {
    override fun onAttach() = record()

    fun update(path: String, route: String?) {
        if (path == this.path && route == this.route) return
        this.path = path
        this.route = route
        if (isAttached) record()
    }

    private fun record() = Traceway.recordNavigation(path, "push", null, route)
}
