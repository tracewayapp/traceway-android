package com.tracewayapp.traceway.minified

import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class MinifiedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Traceway.init(
            this,
            TracewayOptions(
                token = "minified-check",
                baseUrl = BuildConfig.COLLECTOR_URL,
                version = "minified-check",
                environment = "ci",
                // The SDK's own problems in logcat, where the CI job looks for them.
                debug = true,
                remoteConfig = false,
                // Everything is sent as it happens, not kept for an error.
                recordAllSessions = true,
                exportDelayMs = 200,
            ),
        )
    }
}
