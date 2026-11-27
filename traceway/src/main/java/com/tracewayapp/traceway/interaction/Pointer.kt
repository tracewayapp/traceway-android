package com.tracewayapp.traceway.interaction

/**
 * Pointer events of the app's windows, as the touch tracker reports them.
 * Coordinates are in dp (density-independent pixels) from the top-left of
 * the screen, the same space as wireframe frames, so the replay draws the
 * touch where the frame shows the view. Called on the main thread.
 */
internal interface PointerListener {
    fun pointerDown(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {}
    fun pointerMove(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {}

    /** [tap] when the gesture was a tap: short, and without moving past the touch slop. */
    fun pointerUp(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean, tap: Boolean) {}
    fun pointerCancel(pointerId: Int, xDp: Float, yDp: Float, touch: Boolean) {}
}

/**
 * The view (or Compose node) under a tap and its ancestors, nearest first,
 * as recording-trigger selectors see them.
 */
internal interface ElementPath {
    /** Whether [selector] (the CSS subset of `click` steps) matches the target or one of its ancestors. */
    fun matches(selector: String): Boolean

    /**
     * Like [matches], for hover steps: whether the selector matches an
     * element of this path that is not in [previous] (entered, not moved within).
     */
    fun matchesEntering(selector: String, previous: ElementPath?): Boolean
}

/** What a tap is recorded with (`browser.user_action.click`). */
internal class TapTarget(
    /** CSS-like selector of the control that handled the tap, e.g. `MaterialButton#pay`. */
    val selector: String,
    /** The control's type, e.g. `MaterialButton`, or `Button` for a Compose role. */
    val tag: String,
    /** Its id: the view's resource entry name or a Compose `testTag`. */
    val id: String?,
    /** Text it shows; null inside a privacy mask or for text fields. */
    val text: String?,
    /** Its content description / semantics label; null inside a privacy mask. */
    val label: String?,
)
