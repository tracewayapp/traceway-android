package com.tracewayapp.traceway.example

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.tracewayapp.traceway.Traceway
import com.tracewayapp.traceway.network.TracewayOkHttpInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(TracewayOkHttpInterceptor())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(Button(this).apply {
            text = "Throw uncaught exception"
            setOnClickListener {
                throw RuntimeException("boom")
            }
        })

        root.addView(Button(this).apply {
            text = "Capture caught exception"
            setOnClickListener {
                try {
                    throw IllegalStateException("caught example")
                } catch (e: Throwable) {
                    Traceway.captureException(e)
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Record action"
            setOnClickListener {
                Traceway.recordAction(
                    category = "cart",
                    name = "add_item",
                    data = mapOf("sku" to "SKU-123", "qty" to 2),
                )
                println("Added SKU-123 to cart") // captured as a log
            }
        })

        root.addView(Button(this).apply {
            text = "Make HTTP call"
            setOnClickListener {
                Thread {
                    try {
                        httpClient.newCall(
                            Request.Builder().url("https://example.com").build()
                        ).execute().close()
                    } catch (_: IOException) {
                    }
                }.start()
            }
        })

        setContentView(root)
    }
}
