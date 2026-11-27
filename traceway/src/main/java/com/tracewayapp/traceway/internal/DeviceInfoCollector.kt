package com.tracewayapp.traceway.internal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.tracewayapp.traceway.metrics.DeviceDimensions
import java.util.Locale

/**
 * The device and OS as resource attributes of every record. Keys follow
 * the OpenTelemetry semantic conventions where they exist, plus the keys
 * the Flutter SDK sends so the dashboard shows both the same way.
 */
internal object DeviceInfoCollector {
    fun collect(context: Context): Map<String, String> {
        val info = LinkedHashMap<String, String>()
        val release = Build.VERSION.RELEASE ?: ""
        info["os.name"] = "android"
        info["os.type"] = "linux"
        info["os.version"] = release
        info["os.description"] = "Android $release (SDK ${Build.VERSION.SDK_INT})"
        info["android.os.api_level"] = Build.VERSION.SDK_INT.toString()
        info["device.locale"] = currentLocale(context).toString()
        info["runtime.version"] = "Java ${System.getProperty("java.vm.version") ?: System.getProperty("java.version") ?: ""}".trim()

        try {
            val dm = context.resources.displayMetrics
            info["screen.resolution"] = "${dm.widthPixels}x${dm.heightPixels}"
            info["screen.density"] = String.format(Locale.US, "%.1f", dm.density)
        } catch (_: Throwable) {
        }

        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        info["device.model.identifier"] = model
        info["device.model.name"] = model
        info["device.manufacturer"] = manufacturer
        info["device.model"] = model
        info["device.brand"] = Build.BRAND ?: ""
        info["device.modelId"] = Build.DEVICE ?: ""
        info["device.systemVersion"] = "Android $release (SDK ${Build.VERSION.SDK_INT})"
        info["device.isPhysical"] = isPhysicalDevice().toString()

        // os.major, device.form_factor and device.class: the dimensions the server keeps on metrics.
        info.putAll(DeviceDimensions.collect(context))
        return info
    }

    private fun currentLocale(context: Context): Locale {
        val config: Configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: Locale.getDefault()
        }
    }

    private fun isPhysicalDevice(): Boolean {
        val fingerprint = Build.FINGERPRINT?.lowercase(Locale.US) ?: ""
        val model = Build.MODEL?.lowercase(Locale.US) ?: ""
        val product = Build.PRODUCT?.lowercase(Locale.US) ?: ""
        val hardware = Build.HARDWARE?.lowercase(Locale.US) ?: ""
        val emulatorish = fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk") ||
            product.contains("emulator") ||
            product.contains("sdk") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
        return !emulatorish
    }
}
