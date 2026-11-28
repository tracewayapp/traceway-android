package com.tracewayapp.fieldmart.driver

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.tracewayapp.fieldmart.core.resourcePattern
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * The journey's `reveal` (shared/journey.md, Rules): before acting, make the element fully visible: hide the
 * on-screen keyboard if it covers it, then scroll the element's own scroll container with drags (not a programmatic
 * scroll) until it is. Quick drags, released with speed, cover distance; once the element shows, one held drag
 * brings it inside the container. The direction comes from the index numbers of the neighbours on screen
 * (`fm-home-product-12` is before `-17`), forwards otherwise. Every drag is reported for actions.json.
 */
class Reveal(
    private val device: UiDevice,
    private val finder: Finder,
    private val ui: UiAutomation,
    private val input: Input,
    private val density: Float,
    private val record: (kind: String, id: String?, x: Float, y: Float, x2: Float, y2: Float, ms: Long) -> Unit,
    private val log: (String) -> Unit,
) {
    private fun selector(id: String): BySelector = By.res(Pattern.compile(resourcePattern(id)))

    /** The scroll container an element lives in, by its id; null when it is fixed on screen. */
    fun containerOf(id: String): String? = when {
        id.startsWith("fm-home-chip-") -> "fm-home-chips"
        id.startsWith("fm-home-deal-") -> "fm-home-deals"
        id.startsWith("fm-home-product-") || id in setOf("fm-home-deals", "fm-home-hero", "fm-home-chips") -> "fm-home-grid"
        id.startsWith("fm-product-") && id !in setOf("fm-product-add", "fm-product-reviews") -> "fm-product-reviews"
        id.startsWith("fm-feed-row-") -> "fm-feed-list"
        id.startsWith("fm-markets-row-") -> "fm-markets-watchlist"
        id.startsWith("fm-search-result-") -> "fm-search-results"
        id.startsWith("fm-checkout-") && !OVERLAY.matches(id) -> "fm-checkout-form"
        else -> null
    }

    /** The on-screen keyboard's bounds, or null when it is not shown. */
    fun ime(): Rect? = try {
        ui.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    } catch (_: Throwable) {
        null
    }

    /** Returns the element's visible bounds once it is fully visible; throws if it cannot be brought into view. */
    fun reveal(id: String, depth: Int = 0, deadlineMs: Long = SystemClock.uptimeMillis() + 8000): Rect {
        var backwards = false
        var lastSignature: String? = null
        var still = 0
        var nudges = 0
        var lastSize = 0
        var dragged = false
        var ime = ime()
        val cid = containerOf(id)
        while (SystemClock.uptimeMillis() < deadlineMs) {
            val vb = finder.bounds(id)
            val cb: Rect? = if (cid != null) finder.bounds(cid) else largestScrollable()?.visibleBounds
            if (cid != null && cb == null) {
                if (depth > 2) throw IllegalStateException("container $cid of $id not on screen")
                reveal(cid, depth + 1, deadlineMs)
                continue
            }
            if (vb != null) {
                if (ime != null && Rect.intersects(vb, ime)) {
                    log("reveal $id: hiding the keyboard")
                    input.key(KeyEvent.KEYCODE_BACK)
                    SystemClock.sleep(450)
                    ime = ime()
                    continue
                }
                val area = Rect(cb ?: screen())
                if (ime != null && ime.top < area.bottom) area.bottom = ime.top
                val horizontal = cb != null && isHorizontal(cb)
                if (vb.width() > 0 && vb.height() > 0 && inside(vb, area, horizontal)) return if (dragged) settle(id, vb) else vb
                // Larger than its container, or a nudge did not show more of it: as visible as it gets.
                val size = if (horizontal) vb.width() else vb.height()
                if (cb == null || nudges >= 2 || size >= (if (horizontal) area.width() else area.height()) - 2 || (nudges > 0 && size <= lastSize + 1)) {
                    return if (dragged) settle(id, vb) else vb
                }
                nudges++
                lastSize = size
                // Partly visible: one held drag brings it to the middle of its container.
                val shift = if (horizontal) vb.exactCenterX() - area.exactCenterX() else vb.exactCenterY() - area.exactCenterY()
                val limit = (if (horizontal) area.width() else area.height()) * 0.45f
                val d = shift.coerceIn(-limit, limit)
                drag(id, area, horizontal, -d, 240, hold = 100)
                dragged = true
                continue
            }
            if (cb == null) throw IllegalStateException("$id not on screen and nothing to scroll")
            val area = Rect(cb)
            if (ime != null && ime.top < area.bottom) area.bottom = ime.top
            val horizontal = isHorizontal(cb)
            val h = hint(id)
            // The numbered hint gives the direction, unless the list stopped moving that way (then the other way).
            val forward = if (h != null) (h > 0) != backwards else !backwards
            val size = if (horizontal) area.width() else area.height()
            // Quick drags (the list keeps moving after the finger lifts) only to cover distance in a long list towards
            // a numbered element far from the ones on screen; otherwise held drags, which stop where the finger
            // stops, so nothing passes by unseen (a few rows away, a quick drag flew past the row and back again).
            val quick = h != null && abs(h) > QUICK_MIN_ROWS && size > 400 * density
            val sig = cid?.let { signature(it) } ?: ""
            if (sig == lastSignature) {
                still++
                if (still >= 2) {
                    if (backwards) throw IllegalStateException("$id not found in $cid")
                    backwards = true
                    still = 0
                }
            } else {
                still = 0
            }
            lastSignature = sig
            val span = size * (if (quick) 0.7f else 0.75f)
            if (quick) {
                drag(id, area, horizontal, if (forward) -span else span, 160, hold = 0)
                SystemClock.sleep(260)
            } else {
                drag(id, area, horizontal, if (forward) -span else span, 240, hold = 100)
                SystemClock.sleep(60)
            }
            dragged = true
        }
        throw IllegalStateException("$id not revealed in time")
    }

    private fun settle(id: String, first: Rect): Rect {
        var prev = first
        for (i in 0 until 8) {
            SystemClock.sleep(80)
            val now = finder.bounds(id) ?: return prev
            if (now == prev) return now
            prev = now
        }
        return prev
    }

    /**
     * How many numbers the element is after the last visible one (positive: scroll forwards) or before the first
     * (negative); null without numbers or when it is between them.
     */
    private fun hint(id: String): Int? {
        val m = Regex("^(fm-[a-z]+-[a-z]+-)(?:([a-z]+)(\\d+)-)?(\\d+)$").find(id) ?: return null
        val prefix = m.groupValues[1] + (if (m.groupValues[2].isNotEmpty()) "${m.groupValues[2]}${m.groupValues[3]}-" else "")
        val target = m.groupValues[4].toInt()
        val views = Regex.escape(prefix.replace('-', '_'))
        val compose = Regex.escape(prefix)
        val pattern = Pattern.compile("^(?:.*:id/$views|$compose)\\d+$")
        val seen = device.findObjects(By.res(pattern)).mapNotNull { o ->
            o.resourceName?.let { Regex("(\\d+)$").find(it)?.groupValues?.get(1)?.toInt() }
        }
        if (seen.isEmpty()) return null
        return when {
            target > seen.max() -> target - seen.max()
            target < seen.min() -> target - seen.min()
            else -> null
        }
    }

    /** Where the container's content is: the ids and positions of what it shows (a form's one child never moves). */
    private fun signature(cid: String): String = try {
        device.findObject(selector(cid))?.findObjects(By.res(Pattern.compile(".+")))?.take(8)
            ?.joinToString("|") { "${it.resourceName}@${it.visibleBounds.top},${it.visibleBounds.left}" } ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun drag(id: String, area: Rect, horizontal: Boolean, delta: Float, ms: Long, hold: Long) {
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val (x1, y1, x2, y2) = if (horizontal) listOf(cx - delta / 2, cy, cx + delta / 2, cy) else listOf(cx, cy - delta / 2, cx, cy + delta / 2)
        val t = System.currentTimeMillis()
        input.swipe(x1, y1, x2, y2, ms, 16, hold)
        record("reveal", id, x1, y1, x2, y2, System.currentTimeMillis() - t)
    }

    private fun isHorizontal(b: Rect): Boolean = b.width() > b.height() * 2

    private fun largestScrollable(): UiObject2? =
        device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }

    private fun screen(): Rect = Rect(0, 0, device.displayWidth, device.displayHeight)

    /**
     * Inside its container along the scroll axis (the other axis cannot be scrolled). A row cut by the container's
     * edge shows only up to that edge, so an element touching it counts as cut unless it is at the content's end.
     */
    private fun inside(r: Rect, area: Rect, horizontal: Boolean): Boolean =
        if (horizontal) r.left > area.left && r.right < area.right else r.top > area.top && r.bottom < area.bottom

    companion object {
        /** Rows beyond the visible ones from which a reveal uses quick drags (a screen holds about 8–12 rows). */
        private const val QUICK_MIN_ROWS = 12

        /** Checkout elements in dialogs, sheets and popups, not in the form. */
        private val OVERLAY = Regex("^fm-checkout-(terms-agree|terms-text|menu-item-\\d+|edit-plus-\\d+|edit-done|promo-option-\\d+|success|form)$")
    }
}
