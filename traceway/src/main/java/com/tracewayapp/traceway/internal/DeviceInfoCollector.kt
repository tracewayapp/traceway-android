package com.tracewayapp.traceway.internal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import java.net.NetworkInterface
import java.util.Locale

internal object DeviceInfoCollector {
    fun collectSync(context: Context): Map<String, String> {
        val info = mutableMapOf<String, String>()
        info["os.name"] = "android"
        info["os.version"] = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        info["device.locale"] = currentLocale(context).toString()
        info["runtime.version"] = "Java ${System.getProperty("java.version") ?: ""}"

        try {
            val dm: DisplayMetrics = context.resources.displayMetrics
            info["screen.resolution"] = "${dm.widthPixels}x${dm.heightPixels}"
            info["screen.density"] = String.format(Locale.US, "%.1f", dm.density)
        } catch (_: Throwable) {
        }

        info["device.model"] = Build.MODEL ?: ""
        info["device.manufacturer"] = Build.MANUFACTURER ?: ""
        info["device.brand"] = Build.BRAND ?: ""
        info["device.modelId"] = Build.DEVICE ?: ""
        info["device.systemVersion"] = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        info["device.isPhysical"] = isPhysicalDevice().toString()

        return info
    }

    fun collectAsync(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return info
            outer@ for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains('.') && !host.contains(':')) {
                        info["device.ip"] = host
                        break@outer
                    }
                }
            }
        } catch (_: Throwable) {
        }
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
