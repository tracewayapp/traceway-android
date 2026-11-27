package com.tracewayapp.traceway.wireframe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.tracewayapp.traceway.internal.Cancellable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The app's windows on screen: activities, dialogs, popups and menus, each
 * with its root view and where it is on the screen.
 *
 * The list comes from the window manager of this process
 * (`WindowManagerGlobal`'s views, read by reflection, as Square's Curtains
 * and Radiography do). Where that cannot be read, the windows of the
 * activities registered with [addActivity] are used instead.
 */
internal class RootViews {
    /** One window: its root view, window type, dim behind it (0-1) and screen position in pixels. */
    class Root(val view: View, val type: Int, val dim: Float, val x: Int, val y: Int)

    private val activities = ArrayList<WeakReference<Activity>>()
    private val location = IntArray(2)
    private val scratch = ArrayList<View>()

    fun addActivity(activity: Activity) {
        synchronized(activities) {
            activities.removeAll { it.get() == null || it.get() === activity }
            activities.add(WeakReference(activity))
        }
    }

    fun removeActivity(activity: Activity) {
        synchronized(activities) { activities.removeAll { it.get() == null || it.get() === activity } }
    }

    /** The visible windows, bottom first, into [out]. Main thread. */
    fun current(out: MutableList<Root>) {
        out.clear()
        scratch.clear()
        if (!WindowList.copyInto(scratch)) {
            synchronized(activities) {
                for (ref in activities) {
                    val decor = ref.get()?.window?.peekDecorView() ?: continue
                    scratch.add(decor)
                }
            }
        }
        // Application windows (and system ones, such as toasts) in the order
        // they were added, each sub-window (popup, menu) right above its parent.
        val subs = ArrayList<Root>(0)
        for (view in scratch) {
            val root = rootOf(view) ?: continue
            if (root.type in FIRST_SUB_WINDOW..LAST_SUB_WINDOW) subs.add(root) else out.add(root)
        }
        out.sortWith(BY_LAYER)
        for (sub in subs) {
            val token = (sub.view.layoutParams as? WindowManager.LayoutParams)?.token
            var at = -1
            if (token != null) {
                for (i in out.indices) {
                    if (out[i].view.windowToken === token) at = i
                }
            }
            if (at < 0) {
                out.add(sub)
            } else {
                // After the parent and the sub-windows already placed above it.
                var insert = at + 1
                while (insert < out.size && out[insert].type in FIRST_SUB_WINDOW..LAST_SUB_WINDOW) insert++
                out.add(insert, sub)
            }
        }
        scratch.clear()
    }

    private fun rootOf(view: View): Root? {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE) return null
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null
        val params = view.layoutParams as? WindowManager.LayoutParams
        val type = params?.type ?: WindowManager.LayoutParams.TYPE_APPLICATION
        val dim = if (params != null && params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
            params.dimAmount.coerceIn(0f, 1f)
        } else {
            0f
        }
        view.getLocationOnScreen(location)
        return Root(view, type, dim, location[0], location[1])
    }

    companion object {
        private const val FIRST_SUB_WINDOW = WindowManager.LayoutParams.FIRST_SUB_WINDOW
        private const val LAST_SUB_WINDOW = WindowManager.LayoutParams.LAST_SUB_WINDOW

        /** Application windows under system ones (toasts, overlays); otherwise as added. */
        private val BY_LAYER = Comparator<Root> { a, b -> layer(a.type).compareTo(layer(b.type)) }

        private fun layer(type: Int): Int = if (type in 1..99) 0 else 1
    }
}

/** `WindowManagerGlobal.getInstance().mViews`: every window's root view in this process. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private object WindowList {
    private class Access(val getInstance: java.lang.reflect.Method, val views: java.lang.reflect.Field)

    @Volatile
    private var access: Access? = null

    @Volatile
    private var failed = false

    /**
     * Read through the singleton each time rather than kept: the list is the
     * same for the life of the process on a device, but a test framework may
     * replace the singleton between tests.
     */
    private fun list(): List<*>? {
        if (failed) return null
        return try {
            var a = access
            if (a == null) {
                val cls = Class.forName("android.view.WindowManagerGlobal")
                val field = cls.getDeclaredField("mViews")
                field.isAccessible = true
                a = Access(cls.getMethod("getInstance"), field)
                access = a
            }
            val list = a.views.get(a.getInstance.invoke(null)) as? List<*>
            if (list == null) failed = true
            list
        } catch (_: Throwable) {
            failed = true
            null
        }
    }

    /** False when the list cannot be read. */
    fun copyInto(out: MutableList<View>): Boolean {
        val list = list() ?: return false
        return try {
            for (item in list.toTypedArray()) {
                if (item is View) out.add(item)
            }
            true
        } catch (_: Throwable) {
            out.clear()
            false
        }
    }
}

/**
 * Frames of this app's windows: captures them with [ViewCapture] and
 * tells the recorder when something drew. An `OnDrawListener` sits on each
 * window; windows that open (a dialog, a menu) are found at the next capture
 * or, while nothing draws, by a check every half second.
 */
internal class ViewFrameSource(
    val roots: RootViews,
    context: Context?,
    private val debug: Boolean = false,
    private val mainThread: MainThread = AndroidMainThread,
) : WireframeFrameSource {
    @Volatile
    private var onDraw: (() -> Unit)? = null

    private val capture = ViewCapture(context) {
        // A glyph's picture is ready (on its own thread): the screen changed.
        mainThread.post(0) { onDraw?.invoke() }
    }

    override var maskAllInputs: Boolean
        get() = capture.maskAllInputs
        set(value) {
            capture.maskAllInputs = value
        }

    private val listener = ViewTreeObserver.OnDrawListener { onDraw?.invoke() }
    private val observed = WeakHashMap<View, ViewTreeObserver>()
    private val windows = ArrayList<RootViews.Root>()
    private val polled = ArrayList<RootViews.Root>()
    private var lastRoots: Array<View?> = arrayOfNulls(0)
    private var poll: Cancellable? = null
    private var captures = 0
    private var nanos = 0L

    override fun observe(onDraw: () -> Unit) {
        this.onDraw = onDraw
        roots.current(polled)
        listen(polled)
        schedulePoll()
    }

    override fun stopObserving() {
        onDraw = null
        poll?.cancel()
        poll = null
        for ((_, observer) in observed) {
            try {
                if (observer.isAlive) observer.removeOnDrawListener(listener)
            } catch (_: Throwable) {
            }
        }
        observed.clear()
        polled.clear()
        windows.clear()
    }

    override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
        val started = System.nanoTime()
        roots.current(windows)
        listen(windows)
        remember(windows)
        val frame = capture.capture(windows, timestampMs, href)
        windows.clear()
        // A window that just opened joins the next frame, once it is in its place.
        if (capture.deferredWindow) mainThread.post(FOLLOW_UP_MS) { onDraw?.invoke() }
        if (debug) {
            nanos += System.nanoTime() - started
            if (++captures % 200 == 0) {
                logDebug(
                    "Traceway: wireframe capture takes ${String.format(java.util.Locale.US, "%.2f", nanos / captures / 1e6)} ms " +
                        "on average (${capture.lastViewCount} views)",
                )
            }
        }
        return frame
    }

    override fun reset() = capture.reset()

    /** Puts the draw listener on windows that do not have it yet; true when one was new. */
    private fun listen(list: List<RootViews.Root>): Boolean {
        var added = false
        for (root in list) {
            val v = root.view
            val observer = v.viewTreeObserver
            if (observed[v] === observer) continue
            try {
                observer.addOnDrawListener(listener)
                observed[v] = observer
                added = true
            } catch (_: Throwable) {
            }
        }
        return added
    }

    /** Whether the windows on screen are others than at the last capture or check. */
    private fun remember(list: List<RootViews.Root>): Boolean {
        var same = list.size == lastRoots.size
        if (same) {
            for (i in list.indices) {
                if (list[i].view !== lastRoots[i]) {
                    same = false
                    break
                }
            }
        }
        if (!same) lastRoots = Array(list.size) { list[it].view }
        return !same
    }

    private fun schedulePoll() {
        poll?.cancel()
        poll = mainThread.post(POLL_MS) {
            poll = null
            val draw = onDraw ?: return@post
            roots.current(polled)
            val added = listen(polled)
            val changed = remember(polled)
            polled.clear()
            if (added || changed) draw()
            schedulePoll()
        }
    }

    private companion object {
        const val POLL_MS = 500L
        const val FOLLOW_UP_MS = 50L
    }
}

/**
 * Follows the activities of the app for a [WireframeRecorder]: their windows
 * are recorded, and the recorder pauses while none is started. A
 * configuration change (a rotation) is not a trip to the background.
 */
internal class ForegroundTracker(
    private val recorder: WireframeRecorder,
    context: Context,
) : Application.ActivityLifecycleCallbacks {
    private val started = WeakHashMap<Activity, Boolean>()

    /** Activities started before the callbacks were registered, which they cannot see. */
    private var startedBefore = isVisible(context)

    init {
        recorder.setForeground(startedBefore)
    }

    private fun update() = recorder.setForeground(started.isNotEmpty() || startedBefore)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = recorder.addActivity(activity)

    override fun onActivityStarted(activity: Activity) {
        started[activity] = true
        recorder.addActivity(activity)
        update()
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        if (started.remove(activity) == null) startedBefore = false
        if (activity.isChangingConfigurations) return
        update()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) = recorder.removeActivity(activity)

    private companion object {
        fun isVisible(context: Context): Boolean = try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        } catch (_: Throwable) {
            true
        }
    }
}
