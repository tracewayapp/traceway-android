@file:JvmName("TracewayMask")

package com.tracewayapp.traceway

import android.view.View

/**
 * How a masked view appears in wireframe recordings. Masks change the
 * recording only; the app looks the same to its user.
 */
sealed class TracewayMaskMode {
    /** A box of [color] (ARGB) in place of the view; nothing inside it is recorded. */
    class Blank internal constructor(val color: Int) : TracewayMaskMode()

    /** The layout is kept, but its text becomes `*` and its images placeholders. */
    object Blur : TracewayMaskMode()

    companion object {
        @JvmStatic
        @JvmOverloads
        fun blank(color: Int = 0xFF000000.toInt()): TracewayMaskMode = Blank(color)

        @JvmStatic
        fun blur(): TracewayMaskMode = Blur
    }
}

/**
 * How this view (and everything inside it) shows in wireframe recordings
 * and tap records: [TracewayMaskMode.blur], [TracewayMaskMode.blank], or
 * null for no mask of its own. `android:tag="traceway-mask"` (blur) or
 * `"traceway-blank"` in a layout masks it too, and reads back here. Masks
 * change the recording only; the app looks the same to its user. From Java:
 * `Traceway.mask(view, mode)` and `Traceway.unmask(view)`.
 */
var View.tracewayMask: TracewayMaskMode?
    get() = guarded("tracewayMask", null) { TracewayMasks.own(this) }
    set(value) = guarded("tracewayMask") { TracewayMasks.set(this, value) }

/** Where masks are recorded and read; the wireframe capture and tap recording honor them. */
internal object TracewayMasks {
    /** `android:tag` values that mask a view from XML. */
    const val TAG_BLUR = "traceway-mask"
    const val TAG_BLANK = "traceway-blank"

    fun set(view: View, mode: TracewayMaskMode?) {
        view.setTag(R.id.traceway_mask, mode)
    }

    /** The mask set on this view itself, not its ancestors. */
    fun own(view: View): TracewayMaskMode? {
        (view.getTag(R.id.traceway_mask) as? TracewayMaskMode)?.let { return it }
        return when (view.tag) {
            TAG_BLUR -> TracewayMaskMode.Blur
            TAG_BLANK -> TracewayMaskMode.blank()
            else -> null
        }
    }

    /** The mask of [view] or its nearest masked ancestor. */
    fun effective(view: View): TracewayMaskMode? {
        var v: Any? = view
        while (v is View) {
            own(v)?.let { return it }
            v = v.parent
        }
        return null
    }
}
