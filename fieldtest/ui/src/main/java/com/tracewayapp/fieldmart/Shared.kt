package com.tracewayapp.fieldmart

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.TypedValue

/** The cart: a count shown on Home's cart badge and the items on Checkout. Main thread. */
object Cart {
    var count = 0
        private set
    private val listeners = ArrayList<() -> Unit>()

    fun add(n: Int) {
        count += n
        listeners.forEach { it() }
    }

    fun listen(l: () -> Unit) {
        listeners.add(l)
    }
}

object BuildInfo {
    fun describe(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return "${context.packageName} ${info.versionName}${if (debuggable) " debuggable" else ""}"
    }
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** "$123.45" from integer cents. */
fun money(cents: Long): String = "$" + com.tracewayapp.fieldmart.core.MarketClock.price(cents)
