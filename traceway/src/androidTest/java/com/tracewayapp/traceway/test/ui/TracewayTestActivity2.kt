package com.tracewayapp.traceway.test.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** Destination Activity used by navigation-related instrumented tests. */
class TracewayTestActivity2 : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val throwOnStart = intent.getBooleanExtra(EXTRA_THROW, false)

        root.addView(Button(this).apply {
            text = "Second screen — throw"
            tag = "throw_after_navigation"
            setOnClickListener {
                throw IllegalStateException("ui:second_throw")
            }
        })

        setContentView(root)

        if (throwOnStart) {
            // Defer to give the lifecycle observer (and the test) a chance to
            // record the navigation transition before we throw.
            window.decorView.post {
                throw IllegalStateException("ui:throw_after_nav_auto")
            }
        }
    }

    companion object {
        const val EXTRA_THROW = "extra_throw"
    }
}
