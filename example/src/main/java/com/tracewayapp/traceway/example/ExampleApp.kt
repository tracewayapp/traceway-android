package com.tracewayapp.traceway.example

import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Traceway.init(
            this,
            TracewayOptions(
                // The project token, from TRACEWAY_TOKEN (see build.gradle.kts).
                token = BuildConfig.TRACEWAY_TOKEN,
                // Traceway Cloud unless TRACEWAY_URL names a self-hosted server.
                baseUrl = BuildConfig.TRACEWAY_URL,
                environment = "example",
                debug = true,
                // Wireframe session replay: the last seconds on screen go up with every exception.
                screenCapture = true,
                propagateTraceHeaderUrls = listOf("https://httpbin.org/"),
            ),
        )
        Traceway.setAttribute("user.id", "example-user")
    }
}
