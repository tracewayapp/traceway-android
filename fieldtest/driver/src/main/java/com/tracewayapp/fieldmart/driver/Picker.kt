package com.tracewayapp.fieldmart.driver

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The journey's `pick` (02-APP-SPEC.md §5.2) with the picker's own UI: open it with a tap, then a dropdown (country)
 * is scrolled until the value shows and that row is tapped; a Material date picker goes to the month and taps the
 * day, then OK. The value of a date is `+<n>d` (n days from today) or `yyyy-mm-dd`.
 */
class Picker(
    private val device: UiDevice,
    private val tap: (String?, Float, Float) -> String,
    private val rec: Recorder,
    /** The run's T0 on the device clock: `+Nd` counts from its day. */
    private val t0: Long,
) {
    /** Opens the picker of [id] unless it is open already, then chooses [value] in it. */
    fun pick(id: String, field: BySelector, value: String): String {
        if (!open()) {
            val o = device.findObject(field) ?: throw IllegalStateException("$id not on screen")
            val r = o.visibleBounds
            tap(id, r.exactCenterX(), r.exactCenterY())
            SystemClock.sleep(600)
        }
        return if (isDate(value) || device.hasObject(By.res(DATE_PICKER))) date(value) else list(value)
    }

    /** A date picker, or a dialog or popup with a list, is on screen. */
    private fun open(): Boolean = device.hasObject(By.res(DATE_PICKER)) || device.hasObject(By.clazz("android.widget.ListView"))

    private fun isDate(v: String) = v.startsWith("+") || Regex("\\d{4}-\\d{2}-\\d{2}").matches(v)

    private fun list(value: String): String {
        for (i in 0 until 40) {
            val row = device.findObject(By.text(value))
            if (row != null) {
                val b = row.visibleBounds
                tap(null, b.exactCenterX(), b.exactCenterY())
                return "picked '$value' after $i scrolls"
            }
            val lists = device.findObjects(By.scrollable(true)).filter { it.className == "android.widget.ListView" }
            val popup = lists.minByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
                ?: throw IllegalStateException("no list to pick '$value' from")
            if (!popup.scroll(directionFor(popup, value), 0.7f)) {
                // At an end: go the other way once.
                popup.scroll(Direction.UP, 1f)
            }
        }
        throw IllegalStateException("'$value' not found in the list")
    }

    /** Alphabetical lists: scroll down while the visible rows sort before the value. */
    private fun directionFor(list: UiObject2, value: String): Direction {
        val texts = list.children.mapNotNull { it.text }
        val last = texts.lastOrNull() ?: return Direction.DOWN
        return if (last.lowercase(Locale.ROOT) < value.lowercase(Locale.ROOT)) Direction.DOWN else Direction.UP
    }

    private fun date(value: String): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = t0
        if (value.startsWith("+")) {
            cal.add(Calendar.DAY_OF_MONTH, value.trim('+', 'd').toInt())
        } else {
            val (y, m, d) = value.split("-").map { it.toInt() }
            cal.set(y, m - 1, d)
        }
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val monthName = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) + " " + cal.get(Calendar.YEAR)
        for (i in 0 until 14) {
            if (device.hasObject(By.textContains(monthName))) break
            val next = device.findObject(By.res(NEXT_MONTH))
                ?: throw IllegalStateException("no next-month button")
            val b = next.visibleBounds
            tap(null, b.exactCenterX(), b.exactCenterY())
            SystemClock.sleep(400)
        }
        // Day cells are described with the full date ("Saturday, December 12"); match the visible month's day.
        val cells = device.findObjects(By.text(day.toString()))
        val cell = cells.firstOrNull { (it.contentDescription ?: "").contains(cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)) }
            ?: cells.firstOrNull() ?: throw IllegalStateException("day $day not shown")
        val b = cell.visibleBounds
        tap(null, b.exactCenterX(), b.exactCenterY())
        SystemClock.sleep(300)
        val ok = device.findObject(By.res(CONFIRM)) ?: device.findObject(By.text("OK"))
            ?: throw IllegalStateException("no OK button")
        val ob = ok.visibleBounds
        tap(null, ob.exactCenterX(), ob.exactCenterY())
        rec.log("date picked $day $monthName")
        return "date $day $monthName"
    }

    companion object {
        private val DATE_PICKER = java.util.regex.Pattern.compile(".*:id/(mtrl_calendar_main_pane|mtrl_picker_header)")
        private val NEXT_MONTH = java.util.regex.Pattern.compile(".*:id/month_navigation_next")
        private val CONFIRM = java.util.regex.Pattern.compile(".*:id/confirm_button")
    }
}
