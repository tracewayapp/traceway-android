package com.tracewayapp.fieldmart.driver

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * The journey's `pick` (02-APP-SPEC.md §5.2) with the picker's own UI: opened with a tap unless it is open already,
 * then a list (the country dialog, alphabetical) is flung towards the value as a person would, judged by the names on
 * screen, and the value's row is tapped; a Material date picker goes to the month and taps the day, then OK. A date is
 * `+<n>d` (n days after the run's T0, on the device clock) or `yyyy-mm-dd`.
 */
class Picker(
    private val device: UiDevice,
    private val input: Input,
    private val tap: (String?, Float, Float) -> String,
    private val rec: Recorder,
    /** The run's T0 on the device clock: `+Nd` counts from its day. */
    private val t0: Long,
) {
    fun pick(id: String, field: BySelector, value: String, budgetMs: Long): String {
        if (!open()) {
            val o = device.findObject(field) ?: throw IllegalStateException("$id not on screen")
            val r = o.visibleBounds
            tap(id, r.exactCenterX(), r.exactCenterY())
            // The picker takes a moment to show (a Material date picker over a second on the emulator).
            val until = SystemClock.uptimeMillis() + minOf(3000L, budgetMs)
            while (!open() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        }
        return if (isDate(value) || device.hasObject(By.res(DATE_PICKER))) date(value) else list(value)
    }

    /** A date picker, or a dialog or popup with a list, is on screen. */
    private fun open(): Boolean = device.hasObject(By.res(DATE_PICKER)) || device.hasObject(By.clazz("android.widget.ListView"))

    private fun isDate(v: String) = v.startsWith("+") || Regex("\\d{4}-\\d{2}-\\d{2}").matches(v)

    private fun rows(list: UiObject2): List<Pair<String, Rect>> = list.children.mapNotNull { c ->
        val t = c.text ?: c.findObject(By.text(Pattern.compile(".+")))?.text ?: return@mapNotNull null
        val b = c.visibleBounds
        if (b.height() <= 0) null else t to b
    }.sortedBy { it.second.top }

    private fun key(s: String) = s.lowercase(Locale.ROOT)

    /** Flings through the list towards [value], halving the throw after each overshoot; taps its row. */
    private fun list(value: String): String {
        var strength = 0.9f
        var lastDir = 0
        var gestures = 0
        val target = key(value)
        while (gestures < 30) {
            val list = device.findObjects(By.clazz("android.widget.ListView")).maxByOrNull { it.visibleBounds.height() }
                ?: throw IllegalStateException("no list to pick '$value' from")
            val area = list.visibleBounds
            val rows = rows(list)
            val hit = rows.firstOrNull { it.first == value && it.second.top >= area.top && it.second.bottom <= area.bottom }
            if (hit != null) {
                tap(null, hit.second.exactCenterX(), hit.second.exactCenterY())
                return "picked '$value' after $gestures gestures"
            }
            if (rows.isEmpty()) throw IllegalStateException("the list shows no rows")
            val dir = when {
                target > key(rows.last().first) -> 1
                target < key(rows.first().first) -> -1
                // Between the rows on screen but cut by the edge: a short drag brings it in.
                else -> if (rows.any { it.first == value && it.second.bottom > area.bottom }) 1 else -1
            }
            if (lastDir != 0 && dir != lastDir) strength /= 2
            lastDir = dir
            val cx = area.exactCenterX()
            val span = area.height() * minOf(0.8f, strength)
            val fromY = if (dir > 0) area.bottom - area.height() * 0.1f else area.top + area.height() * 0.1f
            val toY = if (dir > 0) fromY - span else fromY + span
            // A strong throw keeps the list moving; once it is close, a held drag moves it exactly.
            if (strength >= 0.45f) input.swipe(cx, fromY, cx, toY, 60, 10) else input.swipe(cx, fromY, cx, toY, 220, 16, 120)
            gestures++
            waitStill(list)
        }
        throw IllegalStateException("'$value' not reached in the list")
    }

    private fun waitStill(list: UiObject2) {
        var prev = ""
        val until = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(120)
            val now = try {
                list.children.take(3).joinToString { "${it.text}@${it.visibleBounds.top}" }
            } catch (_: Throwable) {
                ""
            }
            if (now == prev) return
            prev = now
        }
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
        val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)
        val monthName = "$month ${cal.get(Calendar.YEAR)}"
        for (i in 0 until 14) {
            if (device.hasObject(By.textContains(monthName))) break
            val next = device.findObject(By.res(NEXT_MONTH)) ?: throw IllegalStateException("no next-month button")
            val b = next.visibleBounds
            tap(null, b.exactCenterX(), b.exactCenterY())
            SystemClock.sleep(400)
        }
        // Day cells carry the full date in their description ("Saturday, December 12"): the visible month's day.
        val cells = device.findObjects(By.text(day.toString()))
        val cell = cells.firstOrNull { (it.contentDescription ?: "").contains(month) } ?: cells.firstOrNull()
            ?: throw IllegalStateException("day $day not shown")
        val b = cell.visibleBounds
        tap(null, b.exactCenterX(), b.exactCenterY())
        SystemClock.sleep(300)
        val ok = device.findObject(By.res(CONFIRM)) ?: device.findObject(By.text("OK")) ?: throw IllegalStateException("no OK button")
        val ob = ok.visibleBounds
        tap(null, ob.exactCenterX(), ob.exactCenterY())
        rec.log("date picked $day $monthName")
        return "date $day $monthName"
    }

    companion object {
        /** Any part of a Material date picker. */
        private val DATE_PICKER = Pattern.compile(".*:id/(mtrl_calendar_[a-z_]+|mtrl_picker_[a-z_]+|month_navigation_[a-z_]+|confirm_button)")
        private val NEXT_MONTH = Pattern.compile(".*:id/month_navigation_next")
        private val CONFIRM = Pattern.compile(".*:id/confirm_button")
    }
}
