package com.tracewayapp.traceway.interaction

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout

/** A window with one button (`android.R.id.button1`, "Pay") that counts its clicks. */
class InteractionTestActivity : Activity() {
    lateinit var button: Button
    var clicks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        button = Button(this).apply {
            id = android.R.id.button1
            text = "Pay"
            setOnClickListener { clicks++ }
        }
        root.addView(button, FrameLayout.LayoutParams(400, 200))
        setContentView(root)
    }
}
