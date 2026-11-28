package com.tracewayapp.traceway.minified

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.tracewayapp.traceway.Traceway

/** One of each signal, then everything sent: the CI job checks what reached its collector. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Traceway minified check" })
        Traceway.captureMessage("minified check: a log record")
        Traceway.startInteraction("minified check").end()
        Traceway.metric("minified.check", 1.0)
        Traceway.captureException(IllegalStateException("minified check: an exception"))
        Thread {
            Traceway.flush(20_000)
            Log.i(TAG, "flushed")
        }.start()
    }

    private companion object {
        const val TAG = "MinifiedCheck"
    }
}
