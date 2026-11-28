package com.tracewayapp.fieldmart.driver

import android.app.UiAutomation
import android.graphics.Rect
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import com.tracewayapp.fieldmart.core.resourcePattern
import java.util.regex.Pattern

/**
 * Where an element is on screen, quickly. A View id is looked up with one `findAccessibilityNodeInfosByViewId` per
 * window, answered inside the app; UiAutomator's own search reads node after node over binder, which takes hundreds of
 * milliseconds on a busy two-core emulator. Compose test tags (`fm-…` resource names) go through UiAutomator.
 */
class Finder(private val ui: UiAutomation, private val device: UiDevice, private val pkg: String) {
    /** The visible bounds of [id], or null when it is not on screen. */
    fun bounds(id: String): Rect? {
        viewBounds("$pkg:id/${id.replace('-', '_')}")?.let { return it }
        return try {
            device.findObject(By.res(Pattern.compile(resourcePattern(id))))?.visibleBounds
        } catch (_: StaleObjectException) {
            null
        }
    }

    /** A resource name in this package's id space (`month_navigation_next`) or a full one. */
    fun viewBounds(fullId: String): Rect? {
        val windows = try {
            ui.windows
        } catch (_: Throwable) {
            emptyList()
        }
        for (w in windows) {
            val root = try {
                w.root
            } catch (_: Throwable) {
                null
            } ?: continue
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(fullId)
            } catch (_: Throwable) {
                emptyList()
            }
            for (n in nodes) {
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) return r
            }
        }
        return null
    }

    /** True when an element of a Material date picker is on screen. */
    fun datePickerOpen(): Boolean = listOf("confirm_button", "mtrl_picker_header", "mtrl_calendar_main_pane", "month_navigation_next")
        .any { viewBounds("$pkg:id/$it") != null }

    /**
     * Where [text] is drawn, for each visible node whose text contains it: the union of its characters' screen bounds
     * (accessibility's character locations; TextView and Compose text supply them), or the node's bounds when the node
     * gives none. In screen pixels.
     */
    fun drawnText(text: String, limit: Int = 5): List<Rect> {
        val out = ArrayList<Rect>()
        val key = android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        for (w in try { ui.windows } catch (_: Throwable) { emptyList() }) {
            val root = try { w.root } catch (_: Throwable) { null } ?: continue
            val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { emptyList() }
            for (n in nodes) {
                if (out.size >= limit) return out
                if (!n.isVisibleToUser) continue
                val full = n.text?.toString() ?: continue
                val at = full.indexOf(text)
                if (at < 0) continue
                val union = Rect()
                try {
                    val args = android.os.Bundle().apply {
                        putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, at)
                        putInt(android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length)
                    }
                    if (n.refreshWithExtraData(key, args)) {
                        @Suppress("DEPRECATION")
                        val rects = n.extras.getParcelableArray(key)
                        rects?.forEach { r ->
                            val f = r as? android.graphics.RectF ?: return@forEach
                            if (f.width() > 0 && f.height() > 0) union.union(f.left.toInt(), f.top.toInt(), kotlin.math.ceil(f.right).toInt(), kotlin.math.ceil(f.bottom).toInt())
                        }
                    }
                } catch (_: Throwable) {
                }
                if (union.isEmpty) n.getBoundsInScreen(union)
                if (!union.isEmpty) out += union
            }
        }
        return out
    }

    /** Nodes showing exactly [text] (and, if given, whose description contains [description]). */
    fun textBounds(text: String, description: String? = null): Rect? {
        for (w in ui.windows) {
            val root = w.root ?: continue
            for (n in root.findAccessibilityNodeInfosByText(text)) {
                if (!n.isVisibleToUser || n.text?.toString() != text) continue
                if (description != null && n.contentDescription?.toString()?.contains(description) != true) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) return r
            }
        }
        return null
    }
}
