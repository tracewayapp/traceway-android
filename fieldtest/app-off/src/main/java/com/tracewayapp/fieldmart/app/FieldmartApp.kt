package com.tracewayapp.fieldmart.app

import android.app.Application
import android.view.View
import androidx.navigation.NavController
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.LaunchSettings
import com.tracewayapp.fieldmart.Telemetry
import okhttp3.Interceptor

/** The `off` build: no SDK. */
class FieldmartApp : FieldmartApplication() {
    override fun createTelemetry(): Telemetry = NoTelemetry
}

/** Does nothing; the off build has no observability SDK at all. */
object NoTelemetry : Telemetry {
    override val name = "none"
    override val sessionId: String? = null
    override fun start(app: Application, settings: LaunchSettings) {}
    override fun interceptors(): List<Interceptor> = emptyList()
    override fun trackNavigation(nav: NavController) {}
    override fun mask(view: View, blank: Boolean) {}
    override fun captureException(error: Throwable, attributes: Map<String, String>) {}
    override fun captureMessage(message: String) {}
    override fun track(event: String, attributes: Map<String, Any>) {}
    override fun metric(name: String, value: Double, dimensions: Map<String, Any>) {}
    override fun reportFullyDisplayed() {}
}
