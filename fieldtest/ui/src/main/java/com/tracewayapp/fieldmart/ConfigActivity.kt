package com.tracewayapp.fieldmart

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Takes the launch settings as intent extras (`am start -n <pkg>/com.tracewayapp.fieldmart.ConfigActivity --es fmRun
 * …`), stores them synchronously and finishes. It runs in its own `:config` process, where the application starts
 * nothing, so no SDK session begins before the measured cold start that follows (`am force-stop`, then the launcher).
 */
class ConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = LaunchSettings.store(this, intent?.extras)
        Log.i(Markers.TAG, "FM_CONFIG ${if (ok) "ok" else "failed"} ${LaunchSettings.read(this).describe()}")
        finish()
    }
}
