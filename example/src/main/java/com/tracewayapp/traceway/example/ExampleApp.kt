package com.tracewayapp.traceway.example

import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Traceway.init(
            application = this,
            connectionString = BuildConfig.TRACEWAY_DSN,
            options = TracewayOptions(
                version = BuildConfig.VERSION_NAME,
                environment = "example",
                debug = true,
                // Wireframe session replay: the last seconds on screen go up with every exception.
                screenCapture = true,
                proguardUuid = BuildConfig.TRACEWAY_PROGUARD_UUID,
                propagateTraceHeaderUrls = listOf("https://httpbin.org/"),
            ),
        )
        Traceway.setAttribute("user.id", "example-user")
    }
}
