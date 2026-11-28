package com.tracewayapp.fieldmart.app

import android.app.Application
import android.view.View
import androidx.navigation.NavController
import com.tracewayapp.fieldmart.FieldmartApplication
import com.tracewayapp.fieldmart.LaunchSettings
import com.tracewayapp.fieldmart.Telemetry
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.navigation.trackNavigation
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import com.tracewayapp.traceway.tracewayMask
import okhttp3.Interceptor

/** The SDK build. */
class FieldmartApp : FieldmartApplication() {
    override fun createTelemetry(): Telemetry = TracewayTelemetry
}

/** Fieldmart's seam implemented with Traceway 2.1.0, configured per variant as 03-MEASUREMENT.md §1.3 says. */
object TracewayTelemetry : Telemetry {
    override val name = "traceway 2.1.0"

    override val sessionId: String? get() = Traceway.currentSessionId

    override fun start(app: Application, settings: LaunchSettings) {
        Traceway.init(app, options(settings))
    }

    /** The options of each variant; everything not named is the SDK default. */
    fun options(s: LaunchSettings): TracewayOptions {
        val common = TracewayOptions(
            token = s.token,
            baseUrl = s.baseUrl,
            version = "fieldtest-2.1.0",
            environment = "fieldtest",
            attributes = mapOf(
                "fm.run" to s.run,
                "fm.variant" to s.variant,
                "fm.scenario" to s.scenario,
                "fm.target" to s.target,
                "fm.campaign" to s.campaign,
            ),
            remoteConfig = s.remote,
            debug = s.evidence,
        )
        val images = mapOf("replay" to mapOf("images" to "always"))
        val noImages = mapOf("replay" to mapOf("images" to "off"))
        val variant = when (s.variant) {
            "buffer" -> common.copy(screenCapture = true, collection = images)
            "stream-img" -> common.copy(screenCapture = true, recordAllSessions = true, collection = images)
            "stream-noimg" -> common.copy(screenCapture = true, recordAllSessions = true, collection = noImages)
            else -> common
        }
        return s.fps?.let { variant.copy(fps = it) } ?: variant
    }

    override fun interceptors(): List<Interceptor> = listOf(TracewayOkHttpInterceptor())

    override fun trackNavigation(nav: NavController) = Traceway.trackNavigation(nav)

    override fun mask(view: View, blank: Boolean) {
        view.tracewayMask = if (blank) TracewayMaskMode.blank() else TracewayMaskMode.blur()
    }

    override fun captureException(error: Throwable, attributes: Map<String, String>) =
        Traceway.captureException(error, attributes)

    override fun captureMessage(message: String) = Traceway.captureMessage(message)

    override fun track(event: String, attributes: Map<String, Any>) = Traceway.track(event, attributes)

    override fun metric(name: String, value: Double, dimensions: Map<String, Any>) = Traceway.metric(name, value, dimensions)

    override fun reportFullyDisplayed() = Traceway.reportFullyDisplayed()
}
