package com.tracewayapp.traceway.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A checkout form built from plain views: everything on it shows in the wireframe replay. */
class MainActivity : AppCompatActivity() {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(TracewayOkHttpInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Checkout"
            textSize = 28f
            setTextColor(Color.parseColor("#1B1B1F"))
        })
        root.addView(TextView(this).apply {
            text = "2 items · \$48.00"
            textSize = 16f
            setPadding(0, dp(4), 0, dp(16))
        })
        root.addView(EditText(this).apply {
            id = R.id.email
            hint = "Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        })
        val card = EditText(this).apply {
            id = R.id.card_number
            hint = "Card number"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        // Blank in the replay: a box, nothing of what is typed.
        Traceway.mask(card, TracewayMaskMode.blank(Color.DKGRAY))
        root.addView(card)
        root.addView(CheckBox(this).apply { text = "Save card for next time" })
        root.addView(MaterialSwitch(this).apply { text = "Express delivery" })
        root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progress = 60
            setPadding(0, dp(8), 0, dp(8))
        })

        fun button(label: String, id: Int, onClick: (View) -> Unit) = MaterialButton(this).apply {
            text = label
            this.id = id
            setOnClickListener(onClick)
            root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        button("Pay", R.id.pay) {
            Traceway.track("checkout_started", mapOf("items" to 2L))
            println("Paying for 2 items") // a console breadcrumb
            Thread {
                try {
                    httpClient.newCall(Request.Builder().url("https://httpbin.org/status/500").build()).execute().close()
                } catch (_: IOException) {
                }
                try {
                    error("Payment provider returned 500")
                } catch (e: IllegalStateException) {
                    Traceway.captureException(e, mapOf("checkout.step" to "pay"))
                }
            }.start()
        }
        button("Record action", R.id.record_action) {
            Traceway.recordAction(category = "cart", name = "add_item", data = mapOf("sku" to "SKU-123", "qty" to 2))
        }
        button("Send message", R.id.send_message) { Traceway.captureMessage("User opened support chat") }
        button("Open Compose screen", R.id.open_compose) { startActivity(Intent(this, ComposeActivity::class.java)) }
        button("Crash the app", R.id.crash) { throw RuntimeException("Checkout crashed") }

        setContentView(ScrollView(this).apply { addView(root) })
        root.gravity = Gravity.TOP
    }
}
