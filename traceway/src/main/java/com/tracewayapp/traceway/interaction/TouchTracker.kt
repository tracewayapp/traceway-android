package com.tracewayapp.traceway.interaction

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.tracewayapp.traceway.guarded
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Taps and mouse hovers of a window, for recording them and for click and
 * hover steps of recording triggers. Called on the window's thread (the
 * main thread), before the app sees the event, so a tap comes first in the
 * session, before whatever it causes. Hit test with [ViewPath.pathAt].
 */
internal interface TapListener {
    /**
     * A pointer came up within the touch slop of where it went down, before
     * the long-press timeout. ([xPx], [yPx]) is where it went down in
     * [root]'s coordinates, pixels; ([xDp], [yDp]) the same point in dp from
     * the screen's top-left.
     */
    fun onTap(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {}

    /** A mouse moved over [root] without a button pressed (never touch or stylus). */
    fun onHover(root: View, xPx: Float, yPx: Float, xDp: Float, yDp: Float) {}
}

/**
 * Observes the pointer events of the app's windows by wrapping their
 * [Window.Callback]: every activity's (through activity lifecycle
 * callbacks), and others such as dialogs through [track]. The wrapper
 * delegates every call unchanged and only looks at touch and hover events;
 * nothing here can throw into the app or change how it handles a touch.
 *
 * A window whose callback the app replaces later is wrapped again when its
 * activity starts or resumes. A window is never wrapped twice by the same
 * tracker at the top of its callback chain, and when another library wraps
 * the wrapper and it is wrapped again, only the outermost one reports.
 */
internal class TouchTracker {
    private val pointerListeners = CopyOnWriteArrayList<PointerListener>()
    private val tapListeners = CopyOnWriteArrayList<TapListener>()
    private val windows = WeakHashMap<Window, WindowState>()
    private var application: Application? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = trackActivity(activity)
        override fun onActivityStarted(activity: Activity) = trackActivity(activity)
        override fun onActivityResumed(activity: Activity) = trackActivity(activity)
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    fun addPointerListener(listener: PointerListener) {
        pointerListeners.addIfAbsent(listener)
    }

    fun removePointerListener(listener: PointerListener) {
        pointerListeners.remove(listener)
    }

    fun addTapListener(listener: TapListener) {
        tapListeners.addIfAbsent(listener)
    }

    fun removeTapListener(listener: TapListener) {
        tapListeners.remove(listener)
    }

    /**
     * Tracks the windows of every activity from now on. An activity created
     * before this call is tracked from its next start or resume; [track]
     * its window to start at once.
     */
    fun install(application: Application) {
        onMain {
            if (this.application != null) return@onMain
            this.application = application
            application.registerActivityLifecycleCallbacks(lifecycle)
        }
    }

    /** Stops tracking: unregisters the lifecycle callbacks and unwraps every window it can. */
    fun uninstall() {
        onMain {
            application?.unregisterActivityLifecycleCallbacks(lifecycle)
            application = null
            val tracked = synchronized(windows) { windows.keys.toList() }
            for (window in tracked) untrackNow(window)
        }
    }

    /** Tracks [window] (a dialog's, for example). Safe to call again; it never wraps twice. */
    fun track(window: Window) {
        onMain { trackNow(window) }
    }

    /** Stops tracking [window], restoring its callback when the wrapper is still on top. */
    fun untrack(window: Window) {
        onMain { untrackNow(window) }
    }

    private fun trackActivity(activity: Activity?) {
        try {
            activity?.window?.let { trackNow(it) }
        } catch (_: Throwable) {
        }
    }

    private fun trackNow(window: Window) {
        try {
            val current = window.callback ?: return
            if (current is TracewayWindowCallback && current.tracker === this) {
                current.active = true
                return
            }
            synchronized(windows) {
                val state = windows.getOrPut(window) { WindowState() }
                // Another wrapper that keeps replacing ours would otherwise grow the chain forever.
                if (state.wraps >= MAX_WRAPS) return
                state.wraps++
                val wrapper = TracewayWindowCallback(current, this, window, state)
                state.wrappers.add(WeakReference(wrapper))
                window.callback = wrapper
            }
        } catch (_: Throwable) {
        }
    }

    private fun untrackNow(window: Window) {
        try {
            synchronized(windows) {
                val state = windows.remove(window)
                state?.wrappers?.forEach { it.get()?.active = false }
            }
            val current = window.callback
            if (current is TracewayWindowCallback && current.tracker === this) window.callback = current.delegate
        } catch (_: Throwable) {
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    // ── Events ───────────────────────────────────────────────────────────

    internal fun onTouch(state: WindowState, window: Window?, event: MotionEvent) {
        try {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) state.downs.clear()
            if (pointerListeners.isEmpty() && tapListeners.isEmpty()) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) state.downs.clear()
                return
            }
            val root = window?.peekDecorView() ?: return
            val density = density(root)
            // Event coordinates are the window's; the raw ones (of pointer 0) the screen's.
            val offsetX = event.rawX - event.x
            val offsetY = event.rawY - event.y
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val xDp = (x + offsetX) / density
                    val yDp = (y + offsetY) / density
                    val mouse = isMouse(event, i)
                    if (!mouse || isPrimaryButton(event)) {
                        val slop = if (mouse) MOUSE_SLOP_DP * density else touchSlop(root)
                        state.downs.add(Down(id, x, y, xDp, yDp, event.eventTime, slop))
                    }
                    for (listener in pointerListeners) guarded("touch listener") { listener.pointerDown(id, xDp, yDp, !mouse) }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        val x = event.getX(i)
                        val y = event.getY(i)
                        state.down(id)?.let { if (it.movedTo(x, y)) state.downs.remove(it) }
                        val xDp = (x + offsetX) / density
                        val yDp = (y + offsetY) / density
                        val touch = !isMouse(event, i)
                        for (listener in pointerListeners) guarded("touch listener") { listener.pointerMove(id, xDp, yDp, touch) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val down = state.down(id)
                    if (down != null) state.downs.remove(down)
                    if (action == MotionEvent.ACTION_UP) state.downs.clear()
                    val tap = down != null && !down.movedTo(x, y) &&
                        event.eventTime - down.timeMs <= ViewConfiguration.getLongPressTimeout()
                    val xDp = (x + offsetX) / density
                    val yDp = (y + offsetY) / density
                    val touch = !isMouse(event, i)
                    for (listener in pointerListeners) guarded("touch listener") { listener.pointerUp(id, xDp, yDp, touch, tap) }
                    if (tap && down != null) {
                        for (listener in tapListeners) guarded("touch listener") { listener.onTap(root, down.xPx, down.yPx, down.xDp, down.yDp) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    state.downs.clear()
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        val xDp = (event.getX(i) + offsetX) / density
                        val yDp = (event.getY(i) + offsetY) / density
                        val touch = !isMouse(event, i)
                        for (listener in pointerListeners) guarded("touch listener") { listener.pointerCancel(id, xDp, yDp, touch) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    internal fun onGenericMotion(window: Window?, event: MotionEvent) {
        try {
            val action = event.actionMasked
            if (action != MotionEvent.ACTION_HOVER_ENTER && action != MotionEvent.ACTION_HOVER_MOVE) return
            if (event.pointerCount == 0 || !isMouse(event, 0)) return
            if (pointerListeners.isEmpty() && tapListeners.isEmpty()) return
            val root = window?.peekDecorView() ?: return
            val density = density(root)
            val x = event.getX(0)
            val y = event.getY(0)
            val xDp = event.rawX / density
            val yDp = event.rawY / density
            val id = event.getPointerId(0)
            for (listener in pointerListeners) guarded("touch listener") { listener.pointerMove(id, xDp, yDp, false) }
            for (listener in tapListeners) guarded("touch listener") { listener.onHover(root, x, y, xDp, yDp) }
        } catch (_: Throwable) {
        }
    }

    private fun density(root: View): Float {
        val density = root.resources?.displayMetrics?.density ?: 1f
        return if (density > 0f) density else 1f
    }

    private fun touchSlop(root: View): Float = ViewConfiguration.get(root.context).scaledTouchSlop.toFloat()

    private fun isMouse(event: MotionEvent, index: Int): Boolean =
        event.getToolType(index) == MotionEvent.TOOL_TYPE_MOUSE || event.isFromSource(InputDevice.SOURCE_MOUSE)

    /** Only the primary button of a mouse taps; a button state of 0 comes from emulated mice. */
    private fun isPrimaryButton(event: MotionEvent): Boolean {
        val buttons = event.buttonState
        return buttons == 0 || buttons and MotionEvent.BUTTON_PRIMARY != 0
    }

    private companion object {
        const val MAX_WRAPS = 4

        /** Flutter's mouse slop (`kPrecisePointerHitSlop * 4`): a mouse is precise. */
        const val MOUSE_SLOP_DP = 4f
    }
}

/** A pointer that went down and may still become a tap. */
internal class Down(
    val id: Int,
    val xPx: Float,
    val yPx: Float,
    val xDp: Float,
    val yDp: Float,
    val timeMs: Long,
    private val slopPx: Float,
) {
    fun movedTo(x: Float, y: Float): Boolean {
        val dx = x - xPx
        val dy = y - yPx
        return dx * dx + dy * dy > slopPx * slopPx
    }
}

/** What the wrappers of one window share. Never holds the window. */
internal class WindowState {
    val downs = ArrayList<Down>(4)

    /** A wrapper of this window is delegating an event: the ones it wraps only pass it on. */
    var dispatching = false
    var wraps = 0
    val wrappers = ArrayList<WeakReference<TracewayWindowCallback>>(1)

    fun down(id: Int): Down? {
        for (down in downs) if (down.id == id) return down
        return null
    }
}

/** Delegates every call to [delegate]; reports touch and hover events to [tracker] first. */
internal class TracewayWindowCallback(
    val delegate: Window.Callback,
    val tracker: TouchTracker,
    window: Window,
    private val state: WindowState,
) : Window.Callback {
    private val window = WeakReference(window)

    @Volatile
    var active = true

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event == null || !active || state.dispatching) return delegate.dispatchTouchEvent(event)
        tracker.onTouch(state, window.get(), event)
        state.dispatching = true
        try {
            return delegate.dispatchTouchEvent(event)
        } finally {
            state.dispatching = false
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || !active || state.dispatching) return delegate.dispatchGenericMotionEvent(event)
        tracker.onGenericMotion(window.get(), event)
        state.dispatching = true
        try {
            return delegate.dispatchGenericMotionEvent(event)
        } finally {
            state.dispatching = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean = delegate.dispatchKeyEvent(event)

    override fun dispatchKeyShortcutEvent(event: KeyEvent?): Boolean = delegate.dispatchKeyShortcutEvent(event)

    override fun dispatchTrackballEvent(event: MotionEvent?): Boolean = delegate.dispatchTrackballEvent(event)

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent?): Boolean =
        delegate.dispatchPopulateAccessibilityEvent(event)

    override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean =
        delegate.onPreparePanel(featureId, view, menu)

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean =
        delegate.onMenuItemSelected(featureId, item)

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) = delegate.onWindowAttributesChanged(attrs)

    override fun onContentChanged() = delegate.onContentChanged()

    override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)

    override fun onAttachedToWindow() = delegate.onAttachedToWindow()

    override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()

    override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)

    override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

    @RequiresApi(23)
    override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = delegate.onSearchRequested(searchEvent)

    override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? =
        delegate.onWindowStartingActionMode(callback)

    @RequiresApi(23)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        delegate.onWindowStartingActionMode(callback, type)

    override fun onActionModeStarted(mode: ActionMode?) = delegate.onActionModeStarted(mode)

    override fun onActionModeFinished(mode: ActionMode?) = delegate.onActionModeFinished(mode)

    @RequiresApi(24)
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    @RequiresApi(26)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
}

/**
 * The bookkeeping of hover steps: a new path only when the mouse is over
 * another element than before, with the path it was over, so a step
 * matches when the mouse enters a matching element, not while it moves
 * inside it. Main thread.
 */
internal class HoverTracker {
    private var leaf: Any? = null
    private var previous: UiElementPath? = null

    /** The move to ([xPx], [yPx]) in [root], or null while the mouse stays over the same element. */
    fun move(root: View, xPx: Float, yPx: Float): HoverMove? {
        val hit = ViewPath.hitAt(root, xPx, yPx) ?: return null
        val leaf = hit.leaf
        if (leaf == this.leaf) return null
        this.leaf = leaf
        val path = hit.snapshot() ?: return null
        val before = previous
        previous = path
        return HoverMove(path, before)
    }

    /** Forgets what the mouse was over, when no trigger has a hover step any more. */
    fun reset() {
        leaf = null
        previous = null
    }
}

internal class HoverMove(val path: UiElementPath, val previous: UiElementPath?) {
    /** Whether [selector] matches an element the mouse entered with this move. */
    fun entering(selector: String): Boolean = path.matchesEntering(selector, previous)
}
