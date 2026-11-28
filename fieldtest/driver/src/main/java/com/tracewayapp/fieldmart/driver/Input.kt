package com.tracewayapp.fieldmart.driver

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Real input events, injected as the platform does for a finger and a hardware keyboard: taps, flings that end with
 * velocity, slow drags with the finger held, and typing one key event at a time through the key-character map (the
 * keyboard path, unlike `setText`).
 */
class Input(private val ui: UiAutomation) {
    private val keys: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    fun tap(x: Float, y: Float, holdMs: Long = 60) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(holdMs)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    fun longPress(x: Float, y: Float, ms: Long) = tap(x, y, ms)

    /**
     * Moves the finger from (x1, y1) to (x2, y2) in [durationMs], one move every [stepMs], then holds it still for
     * [holdMs] before lifting. A short duration is a fling (the list keeps moving); a long one a drag.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, stepMs: Long, holdMs: Long = 0, released: (() -> Boolean)? = null) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x1, y1)
        // The finger follows the clock: each move goes where the finger is at that moment, without waiting for the
        // app to take the one before (a busy screen would otherwise put the gesture behind its schedule).
        while (true) {
            SystemClock.sleep(stepMs)
            val now = SystemClock.uptimeMillis()
            val f = ((now - down).toFloat() / durationMs).coerceAtMost(1f)
            touch(down, now, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, sync = false)
            if (f >= 1f) break
        }
        if (holdMs > 0) {
            val end = SystemClock.uptimeMillis() + holdMs
            while (SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(minOf(50L, end - SystemClock.uptimeMillis()).coerceAtLeast(1))
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x2, y2, sync = false)
            }
        }
        // A held drag: the finger stays down (still, as a person's would) until the journey's `release`.
        if (released != null) {
            val limit = SystemClock.uptimeMillis() + 120_000
            while (!released() && SystemClock.uptimeMillis() < limit) {
                SystemClock.sleep(50)
                touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x2, y2, sync = false)
            }
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2)
    }

    /** Types [c] as the key events a keyboard produces for it (with Shift where needed). */
    fun type(c: Char): Boolean {
        val events = keys.getEvents(charArrayOf(c)) ?: return false
        // Without waiting for the app to take each key: the typing keeps its pace on a busy screen, in order.
        for (e in events) {
            val now = SystemClock.uptimeMillis()
            val k = KeyEvent(now, now, e.action, e.keyCode, 0, e.metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            if (!ui.injectInputEvent(k, false)) return false
        }
        return true
    }

    fun key(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        ui.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
        ui.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
    }

    private fun touch(down: Long, time: Long, action: Int, x: Float, y: Float, sync: Boolean = true) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            ui.injectInputEvent(e, sync)
        } finally {
            e.recycle()
        }
    }
}
