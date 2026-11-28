package com.tracewayapp.fieldmart

import android.app.Application
import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tracewayapp.fieldmart.data.Api
import okhttp3.OkHttpClient
import java.io.File

/**
 * The application, shared by both builds; each build only says which [Telemetry] it has. The SDK starts here, in
 * `Application.onCreate`, where its docs put it, with the variant the driver stored through [ConfigActivity].
 */
abstract class FieldmartApplication : Application(), ImageLoaderFactory {
    lateinit var settings: LaunchSettings
        private set
    lateinit var telemetry: Telemetry
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var api: Api
        private set

    /** False in [ConfigActivity]'s `:config` process, where nothing of the app starts. */
    var mainProcess = false
        private set

    protected abstract fun createTelemetry(): Telemetry

    protected open fun inMainProcess(): Boolean = isMainProcess(this)

    protected open fun readSettings(): LaunchSettings = LaunchSettings.read(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
        mainProcess = inMainProcess()
        if (!mainProcess) return
        settings = readSettings()
        telemetry = createTelemetry()
        telemetry.start(this, settings)
        Probe.init(this, settings)
        // One client for data and pictures (Coil loads through it), as a product team would set it up.
        http = OkHttpClient.Builder().apply { telemetry.interceptors().forEach { addInterceptor(it) } }.build()
        api = Api(http, settings.fixtures)
        Markers.info("start ${settings.describe()} telemetry=${telemetry.name} sdk=${Build.VERSION.SDK_INT}")
    }

    /** Coil with its defaults (hardware bitmaps on, memory and disk cache), on the app's OkHttp client. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).okHttpClient(http).build()

    companion object {
        lateinit var instance: FieldmartApplication
            private set

        fun of(context: Context): FieldmartApplication = context.applicationContext as FieldmartApplication

        private fun isMainProcess(context: Context): Boolean {
            val name = if (Build.VERSION.SDK_INT >= 28) {
                getProcessName()
            } else {
                try {
                    File("/proc/self/cmdline").readText().trimEnd('\u0000')
                } catch (_: Throwable) {
                    context.packageName
                }
            }
            return name == context.packageName
        }
    }
}
