package com.tracewayapp.traceway.metrics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The coarse device facts the server keeps as metric dimensions
 * (`backend/app/clientmetrics/dims.go`), as resource attributes: `os.major`,
 * `device.form_factor` (phone, tablet, desktop) and `device.class` (low,
 * mid, high). Nothing that identifies a device.
 */
internal object DeviceDimensions {
    fun collect(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            osMajor(Build.VERSION.RELEASE)?.let { out["os.major"] = it }
        } catch (_: Throwable) {
        }
        try {
            val desktop = context.packageManager.hasSystemFeature("org.chromium.arc") ||
                context.packageManager.hasSystemFeature("org.chromium.arc.device_management") ||
                (Build.VERSION.SDK_INT >= 27 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PC))
            out["device.form_factor"] = formFactor(context.resources.configuration.smallestScreenWidthDp, desktop)
        } catch (_: Throwable) {
        }
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            deviceClass(info.totalMem)?.let { out["device.class"] = it }
        } catch (_: Throwable) {
        }
        return out
    }

    /** The leading number of an Android release (`"14"`, `"8.1.0"`); null for a codename. */
    fun osMajor(release: String?): String? = release?.trim()?.let { Regex("^\\d+").find(it)?.value }

    /** Material's breakpoint between a phone and a tablet; ChromeOS and desktop mode are desktops. */
    fun formFactor(smallestWidthDp: Int, desktop: Boolean): String = when {
        desktop -> "desktop"
        smallestWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    /** From the RAM the system reports, a little under the nominal size, as the Flutter SDK does. */
    fun deviceClass(totalBytes: Long): String? {
        if (totalBytes <= 0) return null
        val gb = totalBytes / (1024.0 * 1024 * 1024)
        return if (gb < 3) "low" else if (gb < 6) "mid" else "high"
    }
}
