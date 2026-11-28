package com.tracewayapp.fieldmart

import android.app.Application
import android.view.View
import androidx.navigation.NavController
import okhttp3.Interceptor

/**
 * The one seam between Fieldmart and an observability SDK (02-APP-SPEC.md §1.5). The `off` build links a no-op
 * implementation and has no Traceway code at all; the SDK build implements it with Traceway.
 */
interface Telemetry {
    /** `none` or the SDK and its version, shown on Diagnostics. */
    val name: String

    /** Called once, in `Application.onCreate`, before anything else of the app runs. */
    fun start(app: Application, settings: LaunchSettings)

    /** The SDK's session id, when it has one. */
    val sessionId: String?

    /** Interceptors for the app's one OkHttp client (data and images). */
    fun interceptors(): List<Interceptor>

    /** Compose Navigation screens. Activities and fragments are the SDK's own business. */
    fun trackNavigation(nav: NavController)

    /** Masks a view in replays: [blank] hides it as a box, otherwise its text and pictures are hidden. */
    fun mask(view: View, blank: Boolean)

    fun captureException(error: Throwable, attributes: Map<String, String> = emptyMap())

    fun captureMessage(message: String)

    fun track(event: String, attributes: Map<String, Any>)

    fun metric(name: String, value: Double, dimensions: Map<String, Any> = emptyMap())

    /** Home shows everything it loads (time to full display). */
    fun reportFullyDisplayed()
}
