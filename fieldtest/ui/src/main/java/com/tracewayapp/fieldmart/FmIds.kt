package com.tracewayapp.fieldmart

import android.content.Context
import android.view.View

/**
 * Element ids (02-APP-SPEC.md §4) as resource ids: `fm-home-product-17` is `R.id.fm_home_product_17`. The indexed
 * ones are looked up by name, once, since rows are bound by index.
 */
object FmIds {
    private val cache = HashMap<String, Int>()

    fun res(context: Context, id: String): Int = synchronized(cache) {
        cache.getOrPut(id) { context.resources.getIdentifier(id.replace('-', '_'), "id", context.packageName) }
    }

    /** Gives [view] the resource id of [id], or none when the id has no resource (then UiAutomator cannot find it). */
    fun set(view: View, id: String) {
        val res = res(view.context, id)
        view.id = if (res != 0) res else View.NO_ID
    }
}
