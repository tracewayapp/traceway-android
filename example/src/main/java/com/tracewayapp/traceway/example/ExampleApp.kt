package com.tracewayapp.traceway.example

import android.app.Application
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayOptions

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Traceway.init(
            application = this,
            connectionString = "your-token@https://your-traceway-instance.com/api/report",
            options = TracewayOptions(
                version = "1.0.0",
                debug = true,
            ),
        )
    }
}
