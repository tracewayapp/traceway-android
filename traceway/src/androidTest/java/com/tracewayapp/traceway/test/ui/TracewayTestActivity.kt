package com.tracewayapp.traceway.test.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tracewayapp.traceway.Traceway

/**
 * Lightweight Activity used by instrumented tests. Each button drives one
 * exception-source scenario the user asked to verify.
 *
 * Two production patterns are represented:
 *
 *   * **Explicit capture** — the click handler wraps its own work in a
 *     try/catch that calls [Traceway.captureException]. This is the
 *     recommended pattern for UI handlers (keeps the app alive).
 *     Buttons: "throw_button", "throw_dialog", "throw_after_navigation".
 *
 *   * **Uncaught + default UEH** — work runs on a thread/queue that the
 *     button only schedules; the test relies on Traceway's installed default
 *     uncaught exception handler to catch and report.
 *     Buttons: "throw_background", "throw_main_handler", "navigate_then_throw".
 *
 * The view IDs are tag-based (`tag = "throw_button"` etc.) so Espresso can
 * find them with `withTagValue` without depending on R.java.
 */
class TracewayTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Explicit-capture pattern: wraps in try/catch, hands to Traceway.
        root.addView(button("throw_button", "Throw from button (caught + captured)") {
            try {
                throw IllegalStateException("ui:throw_button")
            } catch (e: Throwable) {
                Traceway.captureException(e)
            }
        })

        root.addView(button("throw_dialog", "Throw from dialog (caught + captured)") {
            AlertDialog.Builder(this)
                .setTitle("test dialog")
                .setMessage("press OK to throw")
                .setPositiveButton("ok") { _, _ ->
                    try {
                        throw RuntimeException("ui:throw_dialog")
                    } catch (e: Throwable) {
                        Traceway.captureException(e)
                    }
                }
                .show()
        })

        // Uncaught-on-bg-thread pattern: relies on the installed default UEH.
        root.addView(button("throw_background", "Throw from background thread (uncaught)") {
            Thread {
                throw IllegalArgumentException("ui:throw_background")
            }.apply { name = "test-bg-thread" }.start()
        })

        // Uncaught-on-main-via-Handler-post pattern: bypasses Espresso's
        // synchronous throw wrapper so the throw reaches the main thread's
        // default UEH the same way it would in production.
        root.addView(button("throw_main_handler", "Throw via main Handler post (uncaught)") {
            Handler(Looper.getMainLooper()).post {
                throw IllegalStateException("ui:throw_main_handler")
            }
        })

        root.addView(button("println_button", "println a line") {
            println("ui:println_button captured")
        })

        root.addView(button("navigate", "Open second screen") {
            startActivity(Intent(this, TracewayTestActivity2::class.java))
        })

        root.addView(button("navigate_then_throw", "Navigate then throw (uncaught)") {
            val intent = Intent(this, TracewayTestActivity2::class.java)
            intent.putExtra(TracewayTestActivity2.EXTRA_THROW, true)
            startActivity(intent)
        })

        setContentView(root)
    }

    private fun button(tagValue: String, label: String, onClick: View.OnClickListener): Button {
        return Button(this).apply {
            text = label
            tag = tagValue
            setOnClickListener(onClick)
        }
    }
}
