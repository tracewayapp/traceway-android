package com.tracewayapp.traceway

import android.app.Application
import com.tracewayapp.traceway.internal.DeviceInfoCollector
import com.tracewayapp.traceway.internal.ErrorHandler
import com.tracewayapp.traceway.internal.LogCapture
import com.tracewayapp.traceway.internal.NavigationCapture
import java.io.File
import java.util.concurrent.Executors

/**
 * The Traceway public facade.
 *
 * Call [init] from your `Application.onCreate()` — that wraps the entire app:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Traceway.init(
 *             application = this,
 *             connectionString = "your-token@https://your-traceway/api/report",
 *             options = TracewayOptions(version = "1.0.0"),
 *         )
 *     }
 * }
 * ```
 *
 * After init, all uncaught exceptions on every thread are captured
 * automatically, alongside the last ~10s of logs, network calls, and activity
 * transitions. Use [recordAction], [captureException], and [captureMessage]
 * for explicit reporting.
 */
object Traceway {

    /**
     * Wraps the host [application] with Traceway error tracking.
     *
     * Idempotent — safe to call multiple times; the first call wins.
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        application: Application,
        connectionString: String,
        options: TracewayOptions = TracewayOptions(),
    ): TracewayClient {
        TracewayClient.instance?.let { return it }

        val persistDir = if (options.persistToDisk) {
            File(application.noBackupFilesDir ?: application.filesDir, "traceway_pending")
        } else null

        val client = TracewayClient.parseAndCreate(
            connectionString = connectionString,
            options = options,
            persistDir = persistDir,
        )

        val syncInfo = DeviceInfoCollector.collectSync(application)
        client.setDeviceAttributes(syncInfo)

        // Best-effort async device info (network IP) on a background thread.
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "TracewayDeviceInfo").apply { isDaemon = true }
        }.execute {
            val async = DeviceInfoCollector.collectAsync()
            if (async.isNotEmpty()) {
                val merged = LinkedHashMap<String, String>().apply {
                    putAll(syncInfo)
                    putAll(async)
                }
                client.setDeviceAttributes(merged)
            }
        }

        client.loadPendingFromDisk()

        if (options.captureLogs) {
            LogCapture.install()
        }

        if (options.captureNavigation) {
            application.registerActivityLifecycleCallbacks(NavigationCapture())
        }

        ErrorHandler().install()

        return client
    }

    /** Records a custom user-defined breadcrumb. */
    @JvmStatic
    @JvmOverloads
    fun recordAction(category: String, name: String, data: Map<String, Any?>? = null) {
        TracewayClient.instance?.recordAction(category, name, data)
    }

    /** Capture a caught exception. */
    @JvmStatic
    fun captureException(error: Throwable) {
        TracewayClient.instance?.captureException(error)
    }

    /** Capture a free-form message. */
    @JvmStatic
    fun captureMessage(message: String) {
        TracewayClient.instance?.captureMessage(message)
    }

    /** Force a synchronous flush of pending events. */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long? = null) {
        TracewayClient.instance?.flush(timeoutMs)
    }
}
