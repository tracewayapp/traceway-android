package com.tracewayapp.traceway.wireframe

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.AbsSeekBar
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.TracewayMasks
import java.util.Arrays
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the app's windows into a wireframe DOM, the Android counterpart of
 * the iOS view walk and the Flutter widget walk. The pipeline, the same on
 * every platform:
 *
 * roots → walk → convert → VNodes (stable ids, shared unchanged subtrees) →
 * CSS → diff → rrweb events
 *
 * - **roots**: [WireframeRoots] lists the windows on screen (activities,
 *   dialogs, popups), bottom first, with their dim and the keyboard's inset.
 * - **walk**: [capture] visits each window's view tree on the main thread,
 *   after a frame was drawn, in the order views draw (by z, then as listed);
 *   what is invisible, empty or clipped away is skipped. At most 3000
 *   views, 128 deep, are walked per capture, as on iOS.
 * - **convert**: one converter per kind of view, named as on the other
 *   platforms: `text`, `field`, `image`, `toggle`, `slider`, `progress`,
 *   `ratingBar`, `nativePlaceholder` (web views, maps, video, surfaces),
 *   `customDrawing`, `group` (with `scroll` for scrolled content), `dim`
 *   and `keyboard`. Jetpack Compose content is converted from its semantics
 *   tree by [WireframeComposeCapture].
 * - **VNodes**: every view that draws something, clips, scrolls, fades or is
 *   transformed gets an element, nested the way views draw, so CSS clips and
 *   fades its contents like Android does; layout-only groups are folded into
 *   their children's offsets. Geometry is in dp from the screen's top-left,
 *   accumulated while walking. A view keeps its node ids for as long as it
 *   lives, and a view that shows the same as last frame gives back last
 *   frame's node: frames share their unchanged subtrees, and an unchanged
 *   view allocates nothing.
 * - **CSS**: inline styles from the builders in `WireframeCss.kt`, over the
 *   shared stylesheet [wireframeCss]; icon glyphs ([WireframeGlyphs]) and
 *   pictures ([WireframeImages]) are style nodes first in the screen.
 * - **diff** and **rrweb events**: [diffScreens] turns two frames into an
 *   rrweb mutation, and [WireframeRecorder] assembles clips of them.
 */
@SuppressLint("NewApi")
internal class WireframeCapture(context: Context?, onAssetReady: () -> Unit = {}) {
    /** Text in editable fields is masked, as rrweb's `maskAllInputs` does. Password fields always are. */
    @Volatile
    var maskAllInputs = true

    @Volatile
    private var resetRequested = false

    private var recs = WeakHashMap<View, Rec>(256)
    private var nextId = firstCapturedNodeId

    /** The style being built; compared with last frame's before a string is made. */
    @JvmField val sb = StringBuilder(512)

    /** Masked text being built. */
    @JvmField val tb = StringBuilder(128)

    /** An ellipsized text without its fillers. */
    private val eb = StringBuilder(128)
    @JvmField val lists = ListStack()
    private var orders = IntArray(64)
    private var orderTop = 0

    /** dp per pixel. */
    @JvmField var inv = 1f
    @JvmField var density = 1f

    @JvmField val shapes = ShapeSampler()
    @JvmField val glyphs = WireframeGlyphs(onAssetReady)

    /** The pictures image views show, when the replay image policy allows them; off until the client sets it. */
    @JvmField val images = WireframeImages(onAssetReady)
    private var screenPrev: VNode? = null
    private var keyboardPrev: VNode? = null
    private var composeCapture: WireframeComposeCapture? = null
    private var composeChecked = false
    private val rectF = RectF()
    private val rect = Rect()
    private val values = FloatArray(9)
    @JvmField val look = TextLook()
    private val outline = Outline()
    private val accents = WeakHashMap<Resources.Theme, Long>()
    private val typedValue = TypedValue()
    private val customDraw = HashMap<Class<*>, Boolean>()
    private val single = ArrayList<VNode>(1)
    private var nowMs = 0L

    /** Views walked by the last capture, for the debug log. */
    var lastViewCount = 0
        private set

    /**
     * The last error a view's own code threw while it was read (a custom
     * view's getter, say): that view and its subtree were left out of the
     * frame, the rest of the screen was not. For the debug log and tests.
     */
    @Volatile
    var lastError: Throwable? = null
        private set

    private var views = 0

    /** How deep the walk is in the view tree now. */
    private var depth = 0

    /** Windows seen by a capture. */
    private val seenRoots = WeakHashMap<View, Boolean>()

    /**
     * The last capture left out a window that had just opened; it will be in
     * the next one. A window is placed after its first layout: a Compose
     * popup (a menu, a tooltip) is added at the corner of the screen,
     * invisible, and moved to its anchor once its content is measured, so
     * in its first frame it would show in the wrong place.
     */
    @Volatile
    var deferredWindow = false
        private set

    /** Forgets every node, so the next capture shares nothing with earlier ones. */
    fun reset() {
        resetRequested = true
    }

    // ---- frames -----------------------------------------------------------------

    /**
     * The wireframe of [roots] (bottom first), or null when there is nothing
     * to show. The screen is the base application window's extent.
     */
    fun capture(all: List<WireframeRoots.Root>, timestampMs: Long, href: String?): WireframeFrame? {
        val roots = withoutNewWindows(all)
        if (resetRequested) {
            resetRequested = false
            recs = WeakHashMap(256)
            screenPrev = null
            keyboardPrev = null
            composeCapture?.reset()
        }
        lists.reset()
        orderTop = 0
        if (roots.isEmpty()) return null
        nowMs = timestampMs
        views = 0
        depth = 0
        val first = roots[0].view
        density = first.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        inv = 1f / density
        var base = roots[0]
        for (r in roots) {
            if (r.type in 1..99) {
                base = r
                break
            }
        }
        val sw = (base.x + base.view.width).toFloat()
        val sh = (base.y + base.view.height).toFloat()
        if (sw <= 0f || sh <= 0f) return null
        glyphs.shown.clear()
        images.beginCapture(nowMs)
        // The keyboard, when one shows, sits right over the window it types into, as Android places it: over the
        // windows that may take it, under those that say they do not (a dialog without a text field).
        val imeIndex = roots.indexOfFirst { it.ime > 0 }
        var keyboardAfter = imeIndex
        if (imeIndex >= 0) {
            for (i in imeIndex + 1 until roots.size) if (takesKeyboard(roots[i])) keyboardAfter = i
        } else {
            keyboardPrev = null
        }
        val children = lists.acquire()
        try {
            for ((i, root) in roots.withIndex()) {
                val v = root.view
                val rec = rec(v)
                if (root.dim > 0f) children.add(dim(rec, sw, sh, root.dim))
                val x = root.x.toFloat()
                val y = root.y.toFloat()
                visitSafely(
                    v, children, 0f, 0f, x - v.left, y - v.top,
                    max(0f, x), max(0f, y), min(sw, x + v.width), min(sh, y + v.height), MASK_NONE,
                )
                if (i == keyboardAfter) children.add(keyboard(roots[imeIndex], base, sw, sh))
            }
            imageStyles(children, glyphStyles(children))
            val screen = screenNode(children, sw * inv, sh * inv)
            lastViewCount = views
            return WireframeFrame(screen, sw * inv, sh * inv, timestampMs, href)
        } finally {
            lists.reset()
            orderTop = 0
        }
    }

    /**
     * Whether [root]'s window may take the keyboard, so that Android places
     * the keyboard over it: an application window or a popup that is not
     * marked as one that does not need it (`FLAG_ALT_FOCUSABLE_IM`, which an
     * `AlertDialog` without a text field sets, inverts `FLAG_NOT_FOCUSABLE`).
     */
    private fun takesKeyboard(root: WireframeRoots.Root): Boolean {
        val type = root.type
        if (type !in 1..99 && type !in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW) return false
        val params = root.view.layoutParams as? WindowManager.LayoutParams ?: return true
        return WindowManager.LayoutParams.mayUseInputMethod(params.flags)
    }

    /**
     * The keyboard as a neutral box over the bottom of the screen, its top
     * where [imeRoot]'s inset says, across [base], the same box the iOS SDK
     * draws: the keyboard is another app's window, and its keys are never
     * recorded.
     */
    private fun keyboard(imeRoot: WireframeRoots.Root, base: WireframeRoots.Root, sw: Float, sh: Float): VNode {
        val v = base.view
        val top = (imeRoot.y + imeRoot.view.height - imeRoot.ime).toFloat().coerceIn(0f, sh)
        val left = base.x.toFloat().coerceIn(0f, sw)
        val right = min(sw, (base.x + v.width).toFloat())
        val bottom = min(sh, (base.y + v.height).toFloat())
        val dark = (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        sb.setLength(0)
        sb.append("left:").appendPx(left * inv).append(";top:").appendPx(top * inv)
            .append(";width:").appendPx(max(0f, right - left) * inv).append(";height:").appendPx(max(0f, bottom - top) * inv)
            .append(';')
            .append(if (dark) "background-color:#2c2c2e;color:#8e8e93;" else "background-color:#d1d3d9;color:#6d6d72;")
            .append("display:flex;align-items:center;justify-content:center;font-size:13px;")
        val prev = keyboardPrev
        if (prev != null && contentEquals(prev.attributes["style"], sb)) return prev
        val node = VNode.element(
            keyboardNodeId, "div", mapOf("style" to sb.toString()),
            listOf(VNode.text(keyboardLabelTextId, "Keyboard")),
        )
        keyboardPrev = node
        return node
    }

    /** The shade a dialog's window casts over the windows below it, [amount] 0-1, over the whole screen. */
    private fun dim(rec: Rec, sw: Float, sh: Float, amount: Float): VNode {
        sb.setLength(0)
        sb.append("left:0;top:0;width:").appendPx(sw * inv).append(";height:").appendPx(sh * inv)
            .append(";background-color:").appendColor(withAlpha(0xFF000000.toInt(), amount)).append(';')
        return element(rec, DIM_ROLE, null, EMPTY)
    }

    /** [roots] without the windows above the first that no capture has seen yet (see [deferredWindow]). */
    private fun withoutNewWindows(roots: List<WireframeRoots.Root>): List<WireframeRoots.Root> {
        deferredWindow = false
        var kept: ArrayList<WireframeRoots.Root>? = null
        for (i in roots.indices) {
            val r = roots[i]
            val first = seenRoots.put(r.view, true) == null
            if (i > 0 && first) {
                if (kept == null) kept = ArrayList(roots.subList(0, i))
                deferredWindow = true
            } else {
                kept?.add(r)
            }
        }
        return kept ?: roots
    }

    private fun screenNode(children: List<VNode>, w: Float, h: Float): VNode {
        sb.setLength(0)
        sb.append("width:").appendPx(w).append(";height:").appendPx(h).append(";font-family:").append(SCREEN_FONT)
        val prev = screenPrev
        if (prev != null && contentEquals(prev.attributes["style"], sb) && sameChildren(prev.children, children)) return prev
        val node = VNode.element(
            screenNodeId, "div",
            linkedMapOf("id" to "tw", "style" to sb.toString()),
            ArrayList(children),
        )
        screenPrev = node
        return node
    }

    /**
     * A style node for each icon glyph shown lately, first on the screen (a
     * style draws nothing), in a stable order.
     */
    private fun glyphStyles(children: ArrayList<VNode>): Int {
        for (g in glyphs.shown) g.lastShownMs = nowMs
        var at = 0
        for (g in glyphs.all) {
            val css = g.css ?: continue
            if (nowMs - g.lastShownMs > GLYPH_LINGER_MS) continue
            var node = g.node
            if (node == null) {
                node = VNode.element(nextId++, "style", mapOf("_cssText" to css), emptyList())
                g.node = node
            }
            children.add(at++, node)
        }
        return at
    }

    /**
     * A style node for each picture shown in the last [WireframeImages.LINGER_MS],
     * after the glyphs' (from [at]), in a stable order. None in a frame that
     * may not record pictures: one recorded before the policy changed, or on
     * Wi-Fi, must not ride along in it.
     */
    private fun imageStyles(children: ArrayList<VNode>, at: Int) {
        if (!images.allowed) return
        for (image in images.shown) image.lastShownMs = nowMs
        var i = at
        for (image in images.all) {
            val css = image.css ?: continue
            if (image.failed || nowMs - image.lastShownMs > WireframeImages.LINGER_MS) continue
            var node = image.node
            if (node == null) {
                node = VNode.element(nextId++, "style", mapOf("_cssText" to css), emptyList())
                image.node = node
            }
            children.add(i++, node)
        }
    }

    // ---- nodes ------------------------------------------------------------------

    fun rec(v: View): Rec {
        var r = recs[v]
        if (r == null) {
            r = Rec(nextId++)
            recs[v] = r
        }
        return r
    }

    fun newRec(): Rec = Rec(nextId++)

    /** The id of the node playing [role] for [rec]; the same in every frame. */
    fun id(rec: Rec, role: Int): Int {
        if (role == 0) return rec.id
        var ids = rec.ids
        if (ids == null || ids.size <= role) {
            val grown = IntArray(max(role + 1, (ids?.size ?: 0) * 2).coerceAtLeast(4))
            ids?.copyInto(grown)
            rec.ids = grown
            ids = grown
        }
        if (ids[role] == 0) ids[role] = nextId++
        return ids[role]
    }

    private fun prev(rec: Rec, role: Int): VNode? = if (role < rec.prev.size) rec.prev[role] else null

    private fun keep(rec: Rec, role: Int, node: VNode) {
        if (role >= rec.prev.size) rec.prev = rec.prev.copyOf(max(role + 1, rec.prev.size * 2))
        rec.prev[role] = node
    }

    /**
     * The element for [rec]'s [role] with the style in [sb]: last frame's
     * node when it shows the same, else a new one.
     */
    fun element(rec: Rec, role: Int, cls: String?, children: List<VNode>, tag: String = "div"): VNode {
        val prev = prev(rec, role)
        if (prev != null && prev.tag == tag && prev.attributes["class"] == cls &&
            contentEquals(prev.attributes["style"], sb) && sameChildren(prev.children, children)
        ) {
            return prev
        }
        val attrs = LinkedHashMap<String, String>(4)
        if (cls != null) attrs["class"] = cls
        attrs["style"] = sb.toString()
        val node = VNode.element(id(rec, role), tag, attrs, if (children.isEmpty()) EMPTY else ArrayList(children))
        keep(rec, role, node)
        return node
    }

    fun textNode(rec: Rec, role: Int, text: CharSequence): VNode {
        val prev = prev(rec, role)
        if (prev != null && prev.isText && contentEquals(prev.text, text)) return prev
        val node = VNode.text(id(rec, role), text.toString())
        keep(rec, role, node)
        return node
    }

    /** A one-child list, valid until the next call. */
    fun one(node: VNode): List<VNode> {
        single.clear()
        single.add(node)
        return single
    }

    // ---- walk -------------------------------------------------------------------

    /**
     * Adds [v]'s nodes to [out]. ([ox], [oy]) is the origin of [out]'s
     * element and ([px], [py]) the origin of [v]'s parent's content, both in
     * pixels of the walk's space (the screen, or the local space of the
     * nearest transformed view); ([cl], [ct], [cr], [cb]) is what clips above
     * leave visible in that space, NaN inside a transform.
     */
    private fun visit(
        v: View, out: ArrayList<VNode>,
        ox: Float, oy: Float, px: Float, py: Float,
        cl: Float, ct: Float, cr: Float, cb: Float,
        mask: Int,
    ) {
        if (v.visibility != View.VISIBLE) return
        val alpha = v.alpha
        if (alpha <= 0.002f) return
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return
        // A screen of thousands of views, or one nested deeper than any layout needs, is cut short
        // rather than costing the frame.
        if (views >= MAX_VIEWS) return
        views++
        val plain = v.scaleX == 1f && v.scaleY == 1f && v.rotation == 0f &&
            v.rotationX == 0f && v.rotationY == 0f
        var left = px + v.left
        var top = py + v.top
        if (plain) {
            left += v.translationX
            top += v.translationY
        }
        if (!cl.isNaN()) {
            if (plain) {
                if (left >= cr || top >= cb || left + w <= cl || top + h <= ct) return
            } else {
                rectF.set(0f, 0f, w.toFloat(), h.toFloat())
                v.matrix.mapRect(rectF)
                rectF.offset(left, top)
                if (rectF.left >= cr || rectF.top >= cb || rectF.right <= cl || rectF.bottom <= ct) return
            }
        }
        var m = mask
        val own = TracewayMasks.own(v)
        if (own is TracewayMaskMode.Blank) {
            val rec = rec(v)
            sb.setLength(0)
            geometry(v, (left - ox) * inv, (top - oy) * inv, w, h, plain, alpha)
            sb.append("background:").appendColor(own.color).append(';')
            out.add(element(rec, 0, "mk", EMPTY))
            return
        }
        if (own != null) m = MASK_BLUR
        val rec = rec(v)
        val rl = (left - ox) * inv
        val rt = (top - oy) * inv
        when (v) {
            is ViewGroup -> when {
                v is WebView -> nativePlaceholder(v, rec, out, rl, rt, w, h, plain, alpha, "Web view")
                composeRoot(v) -> group(v, rec, out, ox, oy, left, top, w, h, plain, alpha, cl, ct, cr, cb, m, compose = true)
                isMap(v) -> nativePlaceholder(v, rec, out, rl, rt, w, h, plain, alpha, "Map")
                else -> group(v, rec, out, ox, oy, left, top, w, h, plain, alpha, cl, ct, cr, cb, m, compose = false)
            }
            is EditText -> field(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is TextView -> text(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is ImageView -> image(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is RatingBar -> ratingBar(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is AbsSeekBar -> slider(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is ProgressBar -> progress(v, rec, out, rl, rt, w, h, plain, alpha, m)
            is SurfaceView, is TextureView -> nativePlaceholder(v, rec, out, rl, rt, w, h, plain, alpha, nativeLabel(v))
            else -> if (!v.willNotDraw() && drawsItself(v.javaClass)) {
                customDrawing(v, rec, out, rl, rt, w, h, plain, alpha, m)
            } else {
                box(v, rec, out, rl, rt, w, h, plain, alpha, m)
            }
        }
    }

    /** [visit], but a view whose own code throws costs its nodes only, never the frame. */
    private fun visitSafely(
        v: View, out: ArrayList<VNode>,
        ox: Float, oy: Float, px: Float, py: Float,
        cl: Float, ct: Float, cr: Float, cb: Float,
        mask: Int,
    ) {
        val at = out.size
        try {
            visit(v, out, ox, oy, px, py, cl, ct, cr, cb, mask)
        } catch (t: Throwable) {
            while (out.size > at) out.removeAt(out.size - 1)
            lastError = t
        }
    }

    /** The children of [g], in the order they draw: by z, then as listed. */
    private fun visitChildren(
        g: ViewGroup, out: ArrayList<VNode>,
        ox: Float, oy: Float, px: Float, py: Float,
        cl: Float, ct: Float, cr: Float, cb: Float, mask: Int,
    ) {
        val n = g.childCount
        if (n == 0 || depth >= MAX_DEPTH) return
        depth++
        try {
            visitChildrenInOrder(g, n, out, ox, oy, px, py, cl, ct, cr, cb, mask)
        } finally {
            depth--
        }
    }

    private fun visitChildrenInOrder(
        g: ViewGroup, n: Int, out: ArrayList<VNode>,
        ox: Float, oy: Float, px: Float, py: Float,
        cl: Float, ct: Float, cr: Float, cb: Float, mask: Int,
    ) {
        var lifted = false
        for (i in 0 until n) {
            if (g.getChildAt(i).z != 0f) {
                lifted = true
                break
            }
        }
        if (!lifted) {
            for (i in 0 until n) {
                val c = g.getChildAt(i) ?: continue
                visitSafely(c, out, ox, oy, px, py, cl, ct, cr, cb, mask)
            }
            return
        }
        val start = orderTop
        if (orders.size < start + n) orders = orders.copyOf(max(orders.size * 2, start + n))
        orderTop += n
        val order = orders
        for (i in 0 until n) {
            // Insertion sort by z keeps equal ones in list order, as Android draws them.
            var j = i
            val z = g.getChildAt(i).z
            while (j > 0 && g.getChildAt(order[start + j - 1]).z > z) {
                order[start + j] = order[start + j - 1]
                j--
            }
            order[start + j] = i
        }
        try {
            for (k in 0 until n) {
                val c = g.getChildAt(order[start + k]) ?: continue
                visitSafely(c, out, ox, oy, px, py, cl, ct, cr, cb, mask)
            }
        } finally {
            orderTop = start
        }
    }

    private fun group(
        v: ViewGroup, rec: Rec, out: ArrayList<VNode>,
        ox: Float, oy: Float, left: Float, top: Float, w: Int, h: Int,
        plain: Boolean, alpha: Float,
        cl: Float, ct: Float, cr: Float, cb: Float, m: Int,
        compose: Boolean,
    ) {
        val sx = v.scrollX
        val sy = v.scrollY
        val clipsPadding = v.clipToPadding
        val pl = v.paddingLeft
        val pt = v.paddingTop
        val pr = v.paddingRight
        val pb = v.paddingBottom
        val padded = clipsPadding && (pl or pt or pr or pb) != 0
        val clippedByParent = (v.parent as? ViewGroup)?.clipChildren ?: true
        val rl = (left - ox) * inv
        val rt = (top - oy) * inv
        // Where the children reach: a group that clips them gets
        // overflow:hidden only when one actually spills out.
        var overflows = false
        var overflowsPadding = false
        val n = v.childCount
        for (i in 0 until n) {
            val c = v.getChildAt(i) ?: continue
            if (c.visibility != View.VISIBLE) continue
            val l = c.left + c.translationX - sx
            val t = c.top + c.translationY - sy
            val r = l + c.width
            val b = t + c.height
            if (l < 0f || t < 0f || r > w || b > h) overflows = true
            if (padded && (l < pl || t < pt || r > w - pr || b > h - pb)) overflowsPadding = true
        }
        if (sx != 0 || sy != 0 || v is ScrollView || v is HorizontalScrollView) rec.wrap = true
        if (padded && overflowsPadding) rec.wrap = true
        val bg = background(v, rec, w, h)
        val fg = foregroundOutline(v)
        val clipsOutline = v.clipToOutline
        val needsNode = compose || rec.group || rec.wrap || bg || fg != null || alpha < 0.999f || !plain ||
            (clippedByParent && overflows) || clipsOutline
        if (!needsNode) {
            // Layout only: the children are placed in the parent's element.
            tabIndicator(v, rec, out, rl, rt)
            visitChildren(v, out, ox, oy, left, top, cl, ct, cr, cb, m)
            return
        }
        rec.group = true
        val kids = lists.acquire()
        try {
            if (bg) backgroundNode(v, rec, kids, w, h, m)
            // The children's space: this element's, or its local space when it is transformed.
            val lx = if (plain) left else 0f
            val ly = if (plain) top else 0f
            var ccl = cl
            var cct = ct
            var ccr = cr
            var ccb = cb
            if (!plain) {
                ccl = Float.NaN
                cct = Float.NaN
                ccr = Float.NaN
                ccb = Float.NaN
            } else if (!cl.isNaN() && clippedByParent) {
                ccl = max(cl, left)
                cct = max(ct, top)
                ccr = min(cr, left + w)
                ccb = min(cb, top + h)
            }
            if (compose) {
                composeCapture?.capture(v, rec, kids, m)
            }
            tabIndicator(v, rec, kids, 0f, 0f)
            if (rec.wrap) {
                kids.add(scroll(v, rec, lx, ly, w, h, ccl, cct, ccr, ccb, m))
            } else {
                visitChildren(v, kids, lx, ly, lx, ly, ccl, cct, ccr, ccb, m)
            }
            if (fg != null) foregroundNode(rec, kids, fg)
            sb.setLength(0)
            geometry(v, rl, rt, w, h, plain, alpha)
            if (rec.bgKind != Rec.BG_NONE && !bgInset(rec, w, h)) backgroundCss(v, rec, w, h)
            if (clipsOutline) outlineClip(v)
            else if (clippedByParent && overflows) sb.append("overflow:hidden;")
            out.add(element(rec, 0, null, kids))
        } finally {
            lists.release()
        }
    }

    private var tabIndicatorGetter: java.lang.reflect.Method? = null
    private var tabIndicatorChecked: Class<*>? = null

    /**
     * The selected tab's indicator of a Material TabLayout, which the strip
     * of tabs ([strip], the TabLayout's child) draws itself: where it was
     * last drawn, in its color, at ([x], [y]) dp of [out]'s element. Read
     * through `getTabSelectedIndicator()`, which a minified app may have
     * renamed (then nothing).
     */
    private fun tabIndicator(strip: ViewGroup, rec: Rec, out: ArrayList<VNode>, x: Float, y: Float) {
        val tabs = strip.parent as? ViewGroup ?: return
        if (!tabs.javaClass.name.endsWith("TabLayout") && tabs.javaClass.superclass?.name?.endsWith("TabLayout") != true) return
        if (tabIndicatorChecked !== tabs.javaClass) {
            tabIndicatorChecked = tabs.javaClass
            tabIndicatorGetter = try {
                tabs.javaClass.getMethod("getTabSelectedIndicator")
            } catch (_: Throwable) {
                null
            }
        }
        val d = try {
            tabIndicatorGetter?.invoke(tabs) as? Drawable
        } catch (_: Throwable) {
            null
        } ?: return
        val b = d.bounds
        if (b.isEmpty) return
        val info = shapes.shape(d)
        if (!info.visible) return
        sb.setLength(0)
        sb.append("left:").appendPx(x + b.left * inv).append(";top:").appendPx(y + b.top * inv)
            .append(";width:").appendPx(b.width() * inv).append(";height:").appendPx(b.height() * inv).append(';')
        shapeCss(info, info.fill, b.width().toFloat(), b.height().toFloat())
        out.add(element(rec, TAB_INDICATOR_ROLE, null, EMPTY))
    }

    /**
     * The children of a scrolled or padding-clipped group: a clip box (role
     * 1, inset by the padding when the group clips to it) holding the content
     * shifted by the scroll (role 2), so scrolling changes one style.
     * ([lx], [ly]) is the group's origin in the children's space, and
     * ([cl], [ct], [cr], [cb]) what clips above leave visible there.
     */
    private fun scroll(
        v: ViewGroup, rec: Rec, lx: Float, ly: Float, w: Int, h: Int,
        cl: Float, ct: Float, cr: Float, cb: Float, m: Int,
    ): VNode {
        val sx = v.scrollX
        val sy = v.scrollY
        val clipsPadding = v.clipToPadding
        val wl = if (clipsPadding) v.paddingLeft else 0
        val wt = if (clipsPadding) v.paddingTop else 0
        val wr = if (clipsPadding) v.paddingRight else 0
        val wb = if (clipsPadding) v.paddingBottom else 0
        var ccl = cl
        var cct = ct
        var ccr = cr
        var ccb = cb
        if (!ccl.isNaN()) {
            ccl = max(ccl, lx + wl)
            cct = max(cct, ly + wt)
            ccr = min(ccr, lx + w - wr)
            ccb = min(ccb, ly + h - wb)
        }
        val content = lists.acquire()
        val c: VNode
        try {
            val cx = lx - sx
            val cy = ly - sy
            visitChildren(v, content, cx, cy, cx, cy, ccl, cct, ccr, ccb, m)
            sb.setLength(0)
            sb.append("left:").appendPx((-wl - sx) * inv).append(";top:").appendPx((-wt - sy) * inv).append(';')
            c = element(rec, 2, null, content)
        } finally {
            lists.release()
        }
        sb.setLength(0)
        sb.append("left:").appendPx(wl * inv).append(";top:").appendPx(wt * inv)
            .append(";width:").appendPx((w - wl - wr) * inv).append(";height:").appendPx((h - wt - wb) * inv)
            .append(";overflow:hidden;")
        return element(rec, 1, null, one(c))
    }

    // ---- leaves -----------------------------------------------------------------

    /**
     * An editable text field: drawn like [text], with its typed text masked
     * (`maskAllInputs`), passwords as dots, and a Material field's label
     * floated over its top edge when it is focused or filled.
     */
    private fun field(
        v: EditText, rec: Rec, out: ArrayList<VNode>,
        rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int,
    ) = text(v, rec, out, rl, rt, w, h, plain, alpha, m)

    /**
     * A label or paragraph (any TextView, buttons and toggles included): its
     * background, toggle and compound icons, then its text where its layout
     * draws it.
     */
    private fun text(
        v: TextView, rec: Rec, out: ArrayList<VNode>,
        rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int,
    ) {
        val bg = background(v, rec, w, h)
        val toggle = if (v is CompoundButton) toggleKind(v) else TOGGLE_NONE
        val icons = toggle == TOGGLE_NONE && hasCompoundDrawables(v)
        val needsNode = rec.group || bg || alpha < 0.999f || !plain || toggle != TOGGLE_NONE || icons ||
            v.clipToOutline
        if (!needsNode) {
            addText(v, rec, out, rl, rt, w, h, m)
            return
        }
        rec.group = true
        val kids = lists.acquire()
        try {
            if (bg) backgroundNode(v, rec, kids, w, h, m)
            if (toggle != TOGGLE_NONE) toggle(v as CompoundButton, rec, kids, toggle, w, h)
            if (icons) compoundIcons(v, rec, kids, w, h, m)
            addText(v, rec, kids, 0f, 0f, w, h, m)
            sb.setLength(0)
            geometry(v, rl, rt, w, h, plain, alpha)
            if (rec.bgKind != Rec.BG_NONE && !bgInset(rec, w, h)) backgroundCss(v, rec, w, h)
            if (v.clipToOutline) outlineClip(v)
            out.add(element(rec, 0, null, kids))
        } finally {
            lists.release()
        }
    }

    /** The text of [v] (or its hint) as a box where its layout draws it; ([bl], [bt]) is [v]'s corner. */
    private fun addText(v: TextView, rec: Rec, out: ArrayList<VNode>, bl: Float, bt: Float, w: Int, h: Int, m: Int) {
        val layout = v.layout ?: return
        var shown: CharSequence = layout.text ?: return
        var color = v.currentTextColor
        var hint = false
        // A Material TextInputLayout takes the hint off its field and draws it
        // as a label (a TextInputEditText still answers it from getHint()).
        var ownHint: CharSequence? = v.hint
        val label: String? = when {
            v !is EditText -> null
            ownHint.isNullOrEmpty() -> fieldLabel(v, rec)
            hintFromLayout(v) -> ownHint.toString().also { ownHint = null }
            else -> null
        }
        if (label != null && (shown.isNotEmpty() || v.isFocused)) floatingLabel(v, rec, out, bl, bt, label, m)
        if (shown.isEmpty()) {
            val hintText = ownHint.takeIf { !it.isNullOrEmpty() } ?: label.takeIf { !v.isFocused }
            if (hintText.isNullOrEmpty()) return
            shown = hintText
            color = v.currentHintTextColor
            hint = true
        }
        val password = !hint && isPassword(v)
        val masked = !password && (m == MASK_BLUR || (!hint && maskAllInputs && v is EditText))
        val lineCount = max(1, layout.lineCount)
        val tl = v.totalPaddingLeft
        val tt = v.totalPaddingTop
        val tr = v.totalPaddingRight
        val availW = w - tl - tr
        val availH = h - tt - v.totalPaddingBottom
        val l = look.reset()
        l.fontSize = v.textSize * inv
        l.lineHeight = layout.height.toFloat() / layout.lineCount.coerceAtLeast(1) * inv
        l.color = color
        val tf = v.typeface
        val paint = v.paint
        l.weight = when {
            tf != null && Build.VERSION.SDK_INT >= 28 -> tf.weight
            tf != null && tf.isBold -> 700
            else -> 400
        }
        if (paint.isFakeBoldText && l.weight < 700) l.weight = 700
        l.italic = (tf != null && tf.isItalic) || paint.textSkewX != 0f
        val flags = v.paintFlags
        l.underline = flags and Paint.UNDERLINE_TEXT_FLAG != 0
        l.strike = flags and Paint.STRIKE_THRU_TEXT_FLAG != 0
        l.letterSpacing = v.letterSpacing
        l.monospace = tf == Typeface.MONOSPACE
        val rtl = layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        l.rtl = rtl
        l.align = alignOf(layout.alignment, rtl)
        val boxH: Float
        if (hint) {
            l.lines = if (v.maxLines == 1 || isSingleLine(v)) 1 else 2
            boxH = if (l.lines == 1) layout.height * inv else max(layout.height, availH) * inv
        } else {
            l.lines = lineCount
            val ellipsized = layout.getEllipsisCount(lineCount - 1) > 0
            l.ellipsis = ellipsized
            l.clipped = ellipsized || layout.height > availH + 1 ||
                (lineCount == 1 && layout.getLineWidth(0) > availW + 1)
            boxH = layout.height * inv
            // A cut text is padded with zero-width fillers where the cut is.
            if (ellipsized) shown = withoutFillers(shown)
        }
        // The text first: its nodes are children of the box.
        val kids = lists.acquire()
        try {
            if (password) {
                tb.setLength(0)
                for (i in 0 until shown.length) tb.append('•')
                kids.add(textNode(rec, 2, tb))
            } else if (masked) {
                tb.setLength(0)
                kids.add(textNode(rec, 2, maskText(shown, tb)))
            } else if (shown !is Spanned || !spans(rec, shown, kids, v.linkTextColors?.getColorForState(v.drawableState, 0) ?: l.color)) {
                kids.add(textNode(rec, 2, shown))
            }
            sb.setLength(0)
            sb.append("left:").appendPx(bl + tl * inv).append(";top:").appendPx(bt + tt * inv)
                .append(";width:").appendPx(max(0, availW) * inv).append(";height:").appendPx(boxH).append(';')
            val cls = sb.appendTextCss(l)
            out.add(element(rec, 1, cls, kids))
        } finally {
            lists.release()
        }
    }

    /** [text] without the fillers an ellipsized layout puts after its `…`; [text] itself when it has none. */
    private fun withoutFillers(text: CharSequence): CharSequence {
        var i = 0
        while (i < text.length && text[i] != ELLIPSIS_FILLER) i++
        if (i == text.length) return text
        eb.setLength(0)
        for (k in 0 until text.length) {
            val ch = text[k]
            if (ch != ELLIPSIS_FILLER) eb.append(ch)
        }
        return eb
    }

    /**
     * The label a field's accessibility info names (Android 8+), read once
     * per field: where a TextInputLayout keeps it.
     */
    private fun fieldLabel(v: EditText, rec: Rec): String? {
        if (Build.VERSION.SDK_INT < 26) return null
        if (rec.lookText == null) {
            rec.lookText = try {
                val info = v.createAccessibilityNodeInfo()
                val hint = info.hintText?.toString()
                @Suppress("DEPRECATION") info.recycle()
                hint ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
        return rec.lookText?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether a Material text field's hint is drawn by the TextInputLayout
     * around it rather than by the field. Views inflated from layouts keep
     * their class names in minified apps.
     */
    private fun hintFromLayout(v: EditText): Boolean {
        if (v.javaClass.name.endsWith("TextInputEditText")) return true
        var p = v.parent
        for (i in 0 until 3) {
            if (p !is View) return false
            if (p.javaClass.name.endsWith("TextInputLayout")) return true
            p = p.parent
        }
        return false
    }

    /** A field's label floated over its top edge, as a focused or filled Material field shows it. */
    private fun floatingLabel(v: TextView, rec: Rec, out: ArrayList<VNode>, bl: Float, bt: Float, label: String, m: Int) {
        val shown: CharSequence = if (m == MASK_BLUR) {
            tb.setLength(0)
            maskText(label, tb)
        } else {
            label
        }
        val t = textNode(rec, LABEL_ROLE + 1, shown)
        val size = 12f
        // On the outline of an outlined box; inside a filled box (its background paints), just above the text.
        val filled = rec.bgKind == Rec.BG_SHAPE && (rec.bgFill ushr 24) >= 0x80
        val top = if (filled) bt + v.totalPaddingTop * inv - size * 1.2f else bt - size * 0.6f
        sb.setLength(0)
        sb.append("left:").appendPx(bl + v.totalPaddingLeft * inv).append(";top:").appendPx(top)
            .append(";font-size:").appendPx(size).append(";line-height:").appendPx(size * 1.2f).append(";color:")
            .appendColor(if (v.isFocused) accent(v) else v.currentHintTextColor).append(';')
        out.add(element(rec, LABEL_ROLE, "t n", one(t)))
    }

    /**
     * Rich text as one span per run of the same style, when its spans change
     * how it looks; a link (a clickable span) in [linkColor], underlined, as
     * TextView draws it. False when it is plain.
     */
    private fun spans(rec: Rec, text: Spanned, out: ArrayList<VNode>, linkColor: Int): Boolean {
        val len = text.length
        val spans = text.getSpans(0, len, CharacterStyle::class.java)
        if (spans.isEmpty()) return false
        var styled = false
        for (s in spans) {
            if (s is ForegroundColorSpan || s is StyleSpan || s is AbsoluteSizeSpan || s is RelativeSizeSpan ||
                s is UnderlineSpan || s is StrikethroughSpan || s is BackgroundColorSpan || s is TypefaceSpan ||
                s is ClickableSpan
            ) {
                if (text.getSpanStart(s) < text.getSpanEnd(s)) {
                    styled = true
                    break
                }
            }
        }
        if (!styled) return false
        val base = look
        var i = 0
        var run = 0
        while (i < len && run < MAX_RUNS) {
            var next = text.nextSpanTransition(i, len, CharacterStyle::class.java)
            if (run == MAX_RUNS - 1) next = len
            var color = base.color
            var weight = base.weight
            var italic = base.italic
            var underline = base.underline
            var strike = base.strike
            var size = base.fontSize
            var background = 0
            var mono = base.monospace
            for (s in spans) {
                if (text.getSpanStart(s) > i || text.getSpanEnd(s) < next) continue
                when (s) {
                    is ForegroundColorSpan -> color = s.foregroundColor
                    is StyleSpan -> {
                        if (s.style and Typeface.BOLD != 0) weight = max(weight, 700)
                        if (s.style and Typeface.ITALIC != 0) italic = true
                    }
                    is AbsoluteSizeSpan -> size = if (s.dip) s.size.toFloat() else s.size * inv
                    is RelativeSizeSpan -> size *= s.sizeChange
                    is UnderlineSpan -> underline = true
                    is StrikethroughSpan -> strike = true
                    is BackgroundColorSpan -> background = s.backgroundColor
                    is TypefaceSpan -> if (s.family == "monospace") mono = true
                    is ClickableSpan -> {
                        if (linkColor != 0) color = linkColor
                        underline = true
                    }
                }
            }
            tb.setLength(0)
            tb.append(text, i, next)
            val t = textNode(rec, SPAN_ROLE + 2 * run + 1, tb)
            sb.setLength(0)
            sb.appendFont(color, weight, italic, underline, strike, base.letterSpacing, mono)
            if (size != base.fontSize) sb.append("font-size:").appendPx(size).append(';')
            sb.appendFill(background)
            out.add(element(rec, SPAN_ROLE + 2 * run, null, one(t), "span"))
            i = next
            run++
        }
        return true
    }

    private fun hasCompoundDrawables(v: TextView): Boolean =
        v.compoundPaddingLeft > v.paddingLeft || v.compoundPaddingRight > v.paddingRight ||
            v.compoundPaddingTop > v.paddingTop || v.compoundPaddingBottom > v.paddingBottom

    /** The drawables around a text (a button's icon), each where TextView draws it. */
    private fun compoundIcons(v: TextView, rec: Rec, out: ArrayList<VNode>, w: Int, h: Int, m: Int) {
        val ds = v.compoundDrawables
        val vspace = h - v.compoundPaddingBottom - v.compoundPaddingTop
        val hspace = w - v.compoundPaddingRight - v.compoundPaddingLeft
        for (side in 0 until 4) {
            val d = ds[side] ?: continue
            val b = d.bounds
            val bw = b.width()
            val bh = b.height()
            if (bw <= 0 || bh <= 0) continue
            val x: Int
            val y: Int
            when (side) {
                0 -> {
                    x = v.paddingLeft + b.left
                    y = v.compoundPaddingTop + (vspace - bh) / 2 + b.top
                }
                1 -> {
                    x = v.compoundPaddingLeft + (hspace - bw) / 2 + b.left
                    y = v.paddingTop + b.top
                }
                2 -> {
                    x = w - v.paddingRight - bw + b.left
                    y = v.compoundPaddingTop + (vspace - bh) / 2 + b.top
                }
                else -> {
                    x = v.compoundPaddingLeft + (hspace - bw) / 2 + b.left
                    y = h - v.paddingBottom - bh + b.top
                }
            }
            out.add(icon(rec, ICON_ROLE + side, d, x.toFloat(), y.toFloat(), bw, bh, m))
        }
    }

    /** An icon: its shape when it has been drawn, else a square of its color. */
    private fun icon(rec: Rec, role: Int, d: Drawable, x: Float, y: Float, w: Int, h: Int, m: Int): VNode {
        val color = shapes.dominant(d).takeIf { it != 0 } ?: 0xFF757575.toInt()
        val glyph = if (m == MASK_NONE && glyphable(d)) glyphs.lookup(d, w, h, density) else null
        if (glyph != null) glyphs.shown.add(glyph)
        sb.setLength(0)
        sb.append("left:").appendPx(x * inv).append(";top:").appendPx(y * inv)
            .append(";width:").appendPx(w * inv).append(";height:").appendPx(h * inv)
            .append(";color:").appendColor(color).append(';')
        return element(rec, role, glyph?.iconClass ?: "ic", EMPTY)
    }

    private fun glyphable(d: Drawable): Boolean {
        val leaf = unwrap(d) ?: return false
        return leaf !is ColorDrawable && !pictureLike(leaf)
    }

    /** An ImageView: its background and what its drawable shows. */
    private fun image(
        v: ImageView, rec: Rec, out: ArrayList<VNode>,
        rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int,
    ) {
        val bg = background(v, rec, w, h)
        val d = v.drawable
        if (!bg && !rec.group && plain && alpha >= 0.999f && !v.clipToOutline) {
            // Only the picture: its element goes straight into the parent's.
            if (d != null) picture(v, rec, out, d, rl, rt, w, h, m)
            return
        }
        val kids = lists.acquire()
        try {
            if (bg) backgroundNode(v, rec, kids, w, h, m)
            if (d != null) picture(v, rec, kids, d, 0f, 0f, w, h, m)
            if (kids.isEmpty() && rec.bgKind == Rec.BG_NONE) return
            rec.group = true
            sb.setLength(0)
            geometry(v, rl, rt, w, h, plain, alpha)
            if (rec.bgKind != Rec.BG_NONE && !bgInset(rec, w, h)) backgroundCss(v, rec, w, h)
            if (v.clipToOutline) outlineClip(v)
            out.add(element(rec, 0, null, kids))
        } finally {
            lists.release()
        }
    }

    /** What an ImageView's drawable shows: a color, a shape, an icon or an image placeholder. */
    private fun picture(v: ImageView, rec: Rec, out: ArrayList<VNode>, d: Drawable, bl: Float, bt: Float, w: Int, h: Int, m: Int) {
        val b = d.bounds
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        val pl = v.paddingLeft
        val pt = v.paddingTop
        val cw = w - pl - v.paddingRight
        val ch = h - pt - v.paddingBottom
        if (cw <= 0 || ch <= 0) return
        if (v.scaleType == ImageView.ScaleType.FIT_XY || iw <= 0 || ih <= 0 || (b.width() == cw && b.height() == ch)) {
            rectF.set(b)
        } else {
            rectF.set(b)
            v.imageMatrix.mapRect(rectF)
        }
        rectF.offset(pl.toFloat(), pt.toFloat())
        // Where the whole drawable lands before the view's clip: a recorded picture is sized and offset by it.
        val dl = rectF.left
        val dt = rectF.top
        val dw = rectF.width()
        val dh = rectF.height()
        val clipL = if (v.cropToPadding) pl.toFloat() else 0f
        val clipT = if (v.cropToPadding) pt.toFloat() else 0f
        val clipR = if (v.cropToPadding) (pl + cw).toFloat() else w.toFloat()
        val clipB = if (v.cropToPadding) (pt + ch).toFloat() else h.toFloat()
        if (!rectF.intersect(clipL, clipT, clipR, clipB)) return
        val x = rectF.left
        val y = rectF.top
        val iwPx = rectF.width()
        val ihPx = rectF.height()
        val fade = v.imageAlpha
        val leaf = unwrap(d)
        val small = iwPx * inv <= ICON_MAX_DP && ihPx * inv <= ICON_MAX_DP
        sb.setLength(0)
        sb.append("left:").appendPx(bl + x * inv).append(";top:").appendPx(bt + y * inv)
            .append(";width:").appendPx(iwPx * inv).append(";height:").appendPx(ihPx * inv).append(';')
        if (fade < 255) sb.append("opacity:").appendNum3(fade / 255f).append(';')
        when {
            leaf is ColorDrawable && m != MASK_BLUR -> {
                sb.appendFill(leaf.color)
                out.add(element(rec, 1, null, EMPTY))
            }
            leaf is GradientDrawable && m != MASK_BLUR -> {
                val info = shapes.shape(leaf)
                shapeCss(info, info.fill, iwPx, ihPx)
                out.add(element(rec, 1, null, EMPTY))
            }
            small && leaf != null && !pictureLike(leaf) -> {
                val color = shapes.dominant(d).takeIf { it != 0 } ?: 0xFF757575.toInt()
                sb.append("color:").appendColor(color).append(';')
                val glyph = if (m == MASK_NONE) glyphs.lookup(d, b.width(), b.height(), density) else null
                if (glyph != null) {
                    glyphs.shown.add(glyph)
                    // The glyph is the drawable at its bounds; the box is where it lands.
                }
                out.add(element(rec, 1, glyph?.iconClass ?: "ic", EMPTY))
            }
            else -> {
                val bitmap = if (m == MASK_NONE && images.allowed) WireframeImages.bitmapOf(d) else null
                val picture = bitmap?.let { images.lookup(it, dw * inv, dh * inv) }
                if (picture != null) {
                    sb.append("background-size:").appendPx(dw * inv).append(' ').appendPx(dh * inv)
                        .append(";background-position:").appendPx((dl - x) * inv).append(' ').appendPx((dt - y) * inv).append(';')
                }
                out.add(element(rec, 1, picture?.imageClass ?: "im", EMPTY))
            }
        }
    }

    /** A view of no kind the wireframe knows that draws itself (`onDraw`): its background, marked `cp`. */
    private fun customDrawing(v: View, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int) =
        painted(v, rec, out, rl, rt, w, h, plain, alpha, m, custom = true)

    /** Any other view: its background, when it has one. */
    private fun box(v: View, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int) =
        painted(v, rec, out, rl, rt, w, h, plain, alpha, m, custom = false)

    private fun painted(
        v: View, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int,
        plain: Boolean, alpha: Float, m: Int, custom: Boolean,
    ) {
        val bg = background(v, rec, w, h)
        val fg = foregroundOutline(v)
        if (!bg && fg == null && !custom) return
        rec.group = true
        val kids = lists.acquire()
        try {
            if (bg) backgroundNode(v, rec, kids, w, h, m)
            if (fg != null) foregroundNode(rec, kids, fg)
            sb.setLength(0)
            geometry(v, rl, rt, w, h, plain, alpha)
            if (rec.bgKind != Rec.BG_NONE && !bgInset(rec, w, h)) backgroundCss(v, rec, w, h)
            if (v.clipToOutline) outlineClip(v)
            out.add(element(rec, 0, if (custom) "cp" else null, kids))
        } finally {
            lists.release()
        }
    }

    /** A view whose pixels the wireframe cannot read (a web view, a map, a video): a labeled placeholder. */
    private fun nativePlaceholder(v: View, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, label: String) {
        val t = textNode(rec, 1, label)
        sb.setLength(0)
        geometry(v, rl, rt, w, h, plain, alpha)
        out.add(element(rec, 0, "pv", one(t)))
    }

    private fun nativeLabel(v: View): String {
        var p: Any? = v
        var depth = 0
        while (p is View && depth < 4) {
            val label = nativeViewLabel(p.javaClass.name)
            if (label != "Native view") return label
            p = p.parent
            depth++
        }
        return if (v is TextureView) "Video" else "Native view"
    }

    private fun isMap(v: ViewGroup): Boolean {
        val name = v.javaClass.name
        return name.endsWith("MapView") || name.startsWith("com.google.android.gms.maps.") ||
            name.startsWith("com.mapbox.maps.")
    }

    // ---- controls drawn from their state ----------------------------------------

    private fun toggleKind(v: CompoundButton): Int {
        if (v is ToggleButton) return TOGGLE_NONE
        if (v is Switch) return TOGGLE_SWITCH
        val button = if (Build.VERSION.SDK_INT >= 23) v.buttonDrawable else null
        if (v is RadioButton) return if (button != null || Build.VERSION.SDK_INT < 23) TOGGLE_RADIO else TOGGLE_NONE
        val name = v.javaClass.name
        if (name.contains("Switch")) return TOGGLE_SWITCH
        if (name.contains("Chip")) return TOGGLE_NONE
        if (button == null && Build.VERSION.SDK_INT >= 23) {
            // SwitchCompat and its kin draw a switch where their compound padding makes room for it.
            return if (v.compoundPaddingRight - v.paddingRight >= 24 * density) TOGGLE_SWITCH else TOGGLE_NONE
        }
        return if (v is CheckBox || button != null) TOGGLE_CHECKBOX else TOGGLE_NONE
    }

    /** A checkbox, radio button or switch, drawn from its state where the button draws it. */
    private fun toggle(v: CompoundButton, rec: Rec, out: ArrayList<VNode>, kind: Int, w: Int, h: Int) {
        val checked = v.isChecked
        val accent = accent(v)
        if (kind == TOGGLE_SWITCH) {
            switchToggle(v, rec, out, checked, accent, w, h)
            return
        }
        val button = if (Build.VERSION.SDK_INT >= 23) v.buttonDrawable else null
        val cx: Float
        val cy: Float
        if (button != null && !button.bounds.isEmpty) {
            cx = button.bounds.exactCenterX() * inv
            cy = button.bounds.exactCenterY() * inv
        } else {
            val bw = max(v.compoundPaddingLeft - v.paddingLeft, (32 * density).toInt())
            cx = bw / 2f * inv
            cy = h / 2f * inv
        }
        var active = v.buttonTintList?.getColorForState(CHECKED_STATE, accent) ?: 0
        var inactive = v.buttonTintList?.getColorForState(UNCHECKED_STATE, 0) ?: 0
        if (button != null && (active == 0 || inactive == 0)) {
            val sampled = shapes.dominant(button)
            if (checked && active == 0) active = sampled
            if (!checked && inactive == 0) inactive = sampled
        }
        if (active == 0) active = accent
        if (inactive == 0) inactive = 0xFF757575.toInt()
        if (!v.isEnabled) {
            active = withAlpha(active, 0.38f)
            inactive = withAlpha(inactive, 0.38f)
        }
        val kids = lists.acquire()
        try {
            sb.setLength(0)
            if (kind == TOGGLE_RADIO) {
                val color = if (checked) active else inactive
                if (checked) {
                    WidgetCss.radioDot(sb, color)
                    kids.add(element(rec, TOGGLE_ROLE + 2, null, EMPTY))
                    sb.setLength(0)
                }
                WidgetCss.radio(sb, cx, cy, color)
            } else {
                if (checked) {
                    WidgetCss.checkMark(sb)
                    kids.add(element(rec, TOGGLE_ROLE + 2, null, EMPTY))
                    sb.setLength(0)
                }
                WidgetCss.checkbox(sb, cx, cy, checked, active, inactive)
            }
            out.add(element(rec, TOGGLE_ROLE + 1, null, kids))
        } finally {
            lists.release()
        }
    }

    /**
     * A switch: its track and thumb where they are drawn, in the colors
     * their drawables paint (a Material switch tints them itself, not with
     * the theme's accent); the accent and outline colors when they cannot
     * be read.
     */
    private fun switchToggle(v: CompoundButton, rec: Rec, out: ArrayList<VNode>, on: Boolean, accent: Int, w: Int, h: Int) {
        var trackW: Float
        var trackH: Float
        val cx: Float
        val cy: Float
        val track = switchPart(v, thumb = false)
        if (track != null && !track.bounds.isEmpty) {
            trackW = track.bounds.width() * inv
            trackH = track.bounds.height() * inv
            cx = track.bounds.exactCenterX() * inv
            cy = track.bounds.exactCenterY() * inv
        } else {
            val room = (v.compoundPaddingRight - v.paddingRight) * inv
            trackW = min(max(room - 4f, 36f), 52f)
            trackH = if (trackW >= 48f) 32f else 14f
            cx = (w - v.paddingRight) * inv - trackW / 2
            cy = h / 2f * inv
        }
        val compact = trackH < 24f
        if (compact) {
            trackH = min(trackH, 14f)
            trackW = max(trackW, 34f)
        }
        var active = accent
        var inactive = if (compact) 0xFF9E9E9E.toInt() else DEFAULT_OUTLINE
        var fill = 0
        var thumbColor = 0
        if (track != null && !track.bounds.isEmpty) {
            val info = shapes.shape(track)
            if ((info.fill ushr 24) >= 24) {
                if (on && !compact) active = info.fill else fill = info.fill
            }
            if (!on && info.strokePx > 0f && (info.stroke ushr 24) >= 48) inactive = info.stroke
            val thumbDrawable = switchPart(v, thumb = true)
            if (thumbDrawable != null && !thumbDrawable.bounds.isEmpty) thumbColor = shapes.dominant(thumbDrawable)
        }
        if (!v.isEnabled && thumbColor == 0) {
            active = withAlpha(active, 0.38f)
            inactive = withAlpha(inactive, 0.38f)
        }
        val knob = if (compact) 20f else if (on) 24f else 16f
        val thumb = when {
            thumbColor != 0 -> thumbColor
            compact -> if (on) active else 0xFFFAFAFA.toInt()
            on -> 0xFFFFFFFF.toInt()
            else -> inactive
        }
        sb.setLength(0)
        WidgetCss.switchThumb(sb, trackW, trackH, knob, on, thumb, compact)
        val t = element(rec, TOGGLE_ROLE + 2, null, EMPTY)
        sb.setLength(0)
        WidgetCss.switchTrack(sb, cx, cy, trackW, trackH, on, active, inactive, compact, fill)
        if (compact) sb.append("overflow:visible;")
        out.add(element(rec, TOGGLE_ROLE + 1, null, one(t)))
    }

    private val switchParts = HashMap<Class<*>, Array<java.lang.reflect.Method?>>()

    /**
     * A switch's track or thumb drawable: the framework Switch's, or
     * SwitchCompat's (and MaterialSwitch's) through their public getters,
     * which a minified app may have renamed (then null).
     */
    private fun switchPart(v: CompoundButton, thumb: Boolean): Drawable? {
        if (v is Switch) return if (thumb) v.thumbDrawable else v.trackDrawable
        val methods = switchParts.getOrPut(v.javaClass) {
            fun find(name: String) = try {
                v.javaClass.getMethod(name).takeIf { Drawable::class.java.isAssignableFrom(it.returnType) }
            } catch (_: Throwable) {
                null
            }
            arrayOf(find("getTrackDrawable"), find("getThumbDrawable"))
        }
        return try {
            methods[if (thumb) 1 else 0]?.invoke(v) as? Drawable
        } catch (_: Throwable) {
            null
        }
    }

    /** A rating bar's stars, as text in its color. */
    private fun ratingBar(v: RatingBar, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int) {
        val kids = lists.acquire()
        try {
            val pl = v.paddingLeft
            val cw = (w - pl - v.paddingRight).coerceAtLeast(0) * inv
            val hd = h * inv
            val stars = v.numStars.coerceIn(1, 10)
            val full = Math.round(v.rating).coerceIn(0, stars)
            tb.setLength(0)
            for (i in 0 until stars) tb.append(if (i < full) '★' else '☆')
            val t = textNode(rec, PART_ROLE + 1, tb)
            sb.setLength(0)
            sb.append("left:").appendPx(pl * inv).append(";top:0;width:").appendPx(cw).append(";height:").appendPx(hd)
                .append(";font-size:").appendPx(min(hd * 0.8f, cw / stars * 1.1f)).append(";line-height:").appendPx(hd)
                .append(";color:").appendColor(indicatorColor(v)).append(';')
            kids.add(element(rec, PART_ROLE, "t n", one(t)))
            indicatorBox(v, rec, out, kids, rl, rt, w, h, plain, alpha, m)
        } finally {
            lists.release()
        }
    }

    /** A seek bar: its track, the part up to the value, and the thumb. */
    private fun slider(v: AbsSeekBar, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int) {
        val kids = lists.acquire()
        try {
            val pl = v.paddingLeft
            val cw = (w - pl - v.paddingRight).coerceAtLeast(0) * inv
            var color = indicatorColor(v)
            val fraction = fraction(v)
            var inactive = v.progressBackgroundTintList?.defaultColor ?: withAlpha(color, 0.3f)
            var thumb = v.thumbTintList?.defaultColor ?: color
            val d = v.progressDrawable
            if (d != null && !d.bounds.isEmpty) {
                // What the track paints, before and after the value.
                if (fraction > 0.04f) shapes.pixelAt(d, fraction / 2, 0.5f).takeIf { (it ushr 24) >= 128 }?.let { color = it }
                if (fraction < 0.96f) shapes.pixelAt(d, (1 + fraction) / 2, 0.5f).takeIf { (it ushr 24) >= 24 }?.let { inactive = it }
                v.thumb?.takeIf { !it.bounds.isEmpty }?.let { t -> shapes.dominant(t).takeIf { it != 0 }?.let { thumb = it } }
            }
            val cy = h * inv / 2
            // Right to left, a seek bar fills from the right (mirrorForRtl, on in the platform's styles).
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val at = if (rtl) pl * inv + cw * (1 - fraction) else pl * inv + cw * fraction
            sb.setLength(0)
            WidgetCss.sliderTrack(sb, pl * inv, cw, cy, inactive)
            kids.add(element(rec, PART_ROLE, null, EMPTY))
            sb.setLength(0)
            WidgetCss.sliderTrack(sb, if (rtl) at else pl * inv, cw * fraction, cy, color)
            kids.add(element(rec, PART_ROLE + 1, null, EMPTY))
            sb.setLength(0)
            WidgetCss.sliderThumb(sb, at, cy, thumb)
            kids.add(element(rec, PART_ROLE + 2, null, EMPTY))
            indicatorBox(v, rec, out, kids, rl, rt, w, h, plain, alpha, m)
        } finally {
            lists.release()
        }
    }

    /** A progress indicator, spinners included: a bar when it is wide, a ring otherwise. */
    private fun progress(v: ProgressBar, rec: Rec, out: ArrayList<VNode>, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int) {
        val kids = lists.acquire()
        try {
            val pl = v.paddingLeft
            val cw = (w - pl - v.paddingRight).coerceAtLeast(0) * inv
            val ch = (h - v.paddingTop - v.paddingBottom).coerceAtLeast(0) * inv
            val wd = w * inv
            val hd = h * inv
            var color = indicatorColor(v)
            val value = if (v.isIndeterminate) null else fraction(v)
            // The colors its drawable paints (a Material indicator's own), where it is not animating.
            val d = if (value == null) null else v.progressDrawable?.takeIf { !it.bounds.isEmpty }
            var sampledTrack = 0
            if (d != null && value != null) {
                if (w > h * 2.5f) {
                    if (value > 0.04f) shapes.pixelAt(d, value / 2, 0.5f).takeIf { (it ushr 24) >= 128 }?.let { color = it }
                    if (value < 0.96f) sampledTrack = shapes.pixelAt(d, (1 + value) / 2, 0.5f).takeIf { (it ushr 24) >= 24 } ?: 0
                } else if (value > 0.04f) {
                    // Halfway along the arc, which starts at the top and runs clockwise; the ring may be drawn
                    // smaller than the view (a Material indicator's own size), so outside in.
                    val angle = Math.toRadians(-90.0 + 180.0 * value)
                    for (r in RING_RADII) {
                        val p = shapes.pixelAt(d, 0.5f + r * Math.cos(angle).toFloat(), 0.5f + r * Math.sin(angle).toFloat())
                        if ((p ushr 24) >= 128) {
                            color = p
                            break
                        }
                    }
                }
            }
            if (w > h * 2.5f) {
                val barH = min(ch, 4f).coerceAtLeast(1f)
                val track = sampledTrack.takeIf { it != 0 } ?: v.progressBackgroundTintList?.defaultColor ?: withAlpha(color, 0.24f)
                sb.setLength(0)
                WidgetCss.linearBar(sb, value, color, rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                val bar = element(rec, PART_ROLE + 1, null, EMPTY)
                sb.setLength(0)
                sb.append("left:").appendPx(pl * inv).append(";top:").appendPx((hd - barH) / 2)
                    .append(";width:").appendPx(cw).append(";height:").appendPx(barH).append(';')
                WidgetCss.linearTrack(sb, barH, track)
                kids.add(element(rec, PART_ROLE, null, one(bar)))
            } else {
                val track = v.progressBackgroundTintList?.defaultColor
                sb.setLength(0)
                WidgetCss.circular(sb, wd, hd, value, color, track, stroke = max(2f, min(wd, hd) / 12f))
                kids.add(element(rec, PART_ROLE, null, EMPTY))
            }
            indicatorBox(v, rec, out, kids, rl, rt, w, h, plain, alpha, m)
        } finally {
            lists.release()
        }
    }

    private fun indicatorColor(v: ProgressBar): Int =
        v.progressTintList?.defaultColor ?: v.indeterminateTintList?.defaultColor ?: accent(v)

    private fun fraction(v: ProgressBar): Float {
        val min = if (Build.VERSION.SDK_INT >= 26) v.min else 0
        return if (v.max > min) ((v.progress - min).toFloat() / (v.max - min)).coerceIn(0f, 1f) else 0f
    }

    /** The element of a rating bar, slider or progress indicator around its [parts], with its background. */
    private fun indicatorBox(
        v: ProgressBar, rec: Rec, out: ArrayList<VNode>, parts: ArrayList<VNode>,
        rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float, m: Int,
    ) {
        val bg = background(v, rec, w, h)
        if (bg && bgInset(rec, w, h)) {
            // Under the indicator.
            val at = parts.size
            backgroundNode(v, rec, parts, w, h, m)
            if (parts.size > at) {
                val under = ArrayList(parts.subList(at, parts.size))
                parts.subList(at, parts.size).clear()
                parts.addAll(0, under)
            }
        }
        rec.group = true
        sb.setLength(0)
        geometry(v, rl, rt, w, h, plain, alpha)
        if (bg && !bgInset(rec, w, h)) backgroundCss(v, rec, w, h)
        out.add(element(rec, 0, null, parts))
    }

    // ---- backgrounds ------------------------------------------------------------

    /** Reads [v]'s background into [rec]; whether it draws anything. */
    private fun background(v: View, rec: Rec, w: Int, h: Int): Boolean {
        rec.bgKind = Rec.BG_NONE
        rec.bgShape = null
        rec.bgBitmap = null
        rec.bgPictured = false
        val d = v.background ?: return false
        val leaf = unwrap(d) ?: return false
        if (leaf is RippleDrawable && contentLayers(leaf) == 0) return false
        val b = leaf.bounds
        rec.bgL = b.left
        rec.bgT = b.top
        rec.bgR = if (b.isEmpty) w else b.right
        rec.bgB = if (b.isEmpty) h else b.bottom
        if (b.isEmpty) {
            rec.bgL = 0
            rec.bgT = 0
        }
        val tint = v.backgroundTintList
        if (leaf is ColorDrawable) {
            var color = leaf.color
            if (tint != null) color = withAlpha(tint.getColorForState(v.drawableState, color), (color ushr 24) / 255f)
            if (isTransparent(color)) return false
            rec.bgKind = Rec.BG_COLOR
            rec.bgFill = color
            return true
        }
        if (leaf is LayerDrawable && leaf !is RippleDrawable && tint == null && edges(leaf, rec)) return true
        val info = shapes.shape(leaf)
        if (info.image && leaf is BitmapDrawable) rec.bgBitmap = leaf.bitmap
        rec.bgFill = info.fill
        if (tint != null && leaf is GradientDrawable && info.gradientColors == null) {
            rec.bgFill = withAlpha(tint.getColorForState(v.drawableState, info.fill), (info.fill ushr 24) / 255f)
        }
        if (!info.visible && isTransparent(rec.bgFill)) {
            return v is EditText && underline(v, rec, w, h)
        }
        if (v is EditText && isTransparent(info.fill) && info.strokePx <= 0f && !info.image && info.gradientColors == null) {
            return underline(v, rec, w, h)
        }
        rec.bgKind = Rec.BG_SHAPE
        rec.bgShape = info
        return true
    }

    /**
     * A layer list of two plain colors, as XML draws a one-side border or a
     * divider: the top one inset over the bottom one (the top color is the
     * fill, the bottom one borders as wide as the insets), or a thin line of
     * the top color along one edge of the bottom one.
     */
    private fun edges(d: LayerDrawable, rec: Rec): Boolean {
        if (d.numberOfLayers != 2) return false
        val under = d.getDrawable(0) ?: return false
        val over = d.getDrawable(1) ?: return false
        val edge = solidColor(under) ?: return false
        val fill = solidColor(over) ?: return false
        val ub = under.bounds
        val ob = over.bounds
        if (ub.isEmpty || ob.isEmpty) return false
        val l = ob.left - ub.left
        val t = ob.top - ub.top
        val r = ub.right - ob.right
        val b = ub.bottom - ob.bottom
        if (l < 0 || t < 0 || r < 0 || b < 0 || (l or t or r or b) == 0) return false
        rec.bgKind = Rec.BG_EDGES
        rec.edgeL = 0
        rec.edgeT = 0
        rec.edgeR = 0
        rec.edgeB = 0
        val lineH = ob.height()
        val lineW = ob.width()
        when {
            // A line along one edge over the fill (a tab bar's divider): the fill, and the line as that edge.
            l == 0 && r == 0 && b == 0 && lineH * 4 <= ub.height() -> { rec.bgFill = edge; rec.bgEdge = fill; rec.edgeB = lineH }
            l == 0 && r == 0 && t == 0 && lineH * 4 <= ub.height() -> { rec.bgFill = edge; rec.bgEdge = fill; rec.edgeT = lineH }
            t == 0 && b == 0 && r == 0 && lineW * 4 <= ub.width() -> { rec.bgFill = edge; rec.bgEdge = fill; rec.edgeR = lineW }
            t == 0 && b == 0 && l == 0 && lineW * 4 <= ub.width() -> { rec.bgFill = edge; rec.bgEdge = fill; rec.edgeL = lineW }
            else -> {
                rec.bgFill = fill
                rec.bgEdge = edge
                rec.edgeL = l
                rec.edgeT = t
                rec.edgeR = r
                rec.edgeB = b
            }
        }
        rec.bgL = ub.left
        rec.bgT = ub.top
        rec.bgR = ub.right
        rec.bgB = ub.bottom
        return true
    }

    /** The one color [d] paints all over, or null when it paints a shape, a gradient or a picture. */
    private fun solidColor(d: Drawable): Int? {
        val leaf = unwrap(d) ?: return null
        if (leaf is ColorDrawable) return leaf.color
        if (Build.VERSION.SDK_INT >= 24 && leaf is GradientDrawable && leaf.shape == GradientDrawable.RECTANGLE &&
            leaf.cornerRadius == 0f && leaf.cornerRadii == null && (leaf.colors?.size ?: 0) < 2
        ) {
            return leaf.color?.getColorForState(leaf.state, 0)
        }
        return null
    }

    /**
     * A text field whose background draws only an underline: at the bottom
     * of where the background draws, inside its insets (AppCompat's field
     * background is inset 4 dp at the sides and 7 dp at the bottom).
     */
    private fun underline(v: EditText, rec: Rec, w: Int, h: Int): Boolean {
        rec.bgKind = Rec.BG_UNDERLINE
        rec.bgFill = if (v.isFocused) accent(v) else withAlpha(v.currentHintTextColor, 1f)
        if (rec.bgR <= rec.bgL || rec.bgB <= rec.bgT || rec.bgB > h || rec.bgR > w) {
            rec.bgL = 0
            rec.bgT = 0
            rec.bgR = w
            rec.bgB = h
        }
        return true
    }

    private fun bgInset(rec: Rec, w: Int, h: Int): Boolean =
        rec.bgL != 0 || rec.bgT != 0 || rec.bgR != w || rec.bgB != h

    /**
     * The background as its own element, when it does not fill the view (a
     * button's inset shape); before it, a bitmap background's picture when
     * the frame records pictures and [m] masks nothing.
     */
    private fun backgroundNode(v: View, rec: Rec, out: ArrayList<VNode>, w: Int, h: Int, m: Int) {
        if (rec.bgKind == Rec.BG_NONE) return
        val bw = rec.bgR - rec.bgL
        val bh = rec.bgB - rec.bgT
        if (bw <= 0 || bh <= 0) return
        val bitmap = rec.bgBitmap
        if (bitmap != null && m == MASK_NONE && images.allowed) {
            val picture = images.lookup(bitmap, bw * inv, bh * inv)
            if (picture != null) {
                rec.bgPictured = true
                sb.setLength(0)
                sb.append("left:").appendPx(rec.bgL * inv).append(";top:").appendPx(rec.bgT * inv)
                    .append(";width:").appendPx(bw * inv).append(";height:").appendPx(bh * inv)
                    .append(";background-size:100% 100%;")
                out.add(element(rec, BG_PICTURE_ROLE, picture.imageClass, EMPTY))
            }
        }
        if (!bgInset(rec, w, h)) return
        sb.setLength(0)
        sb.append("left:").appendPx(rec.bgL * inv).append(";top:").appendPx(rec.bgT * inv)
            .append(";width:").appendPx(bw * inv).append(";height:").appendPx(bh * inv).append(';')
        backgroundCss(v, rec, bw, bh)
        out.add(element(rec, BG_ROLE, null, EMPTY))
    }

    /**
     * The drawable of [v]'s foreground when it draws an outline (an outlined
     * MaterialCardView's stroke, which the card draws over its content), or
     * null. Only the outline is kept: what else a foreground paints is a
     * ripple's press or a scrim.
     */
    private fun foregroundOutline(v: View): Drawable? {
        val d = (if (Build.VERSION.SDK_INT >= 23) v.foreground else (v as? FrameLayout)?.foreground) ?: return null
        val leaf = unwrap(d) ?: return null
        if (leaf is RippleDrawable && contentLayers(leaf) == 0) return null
        if (leaf.bounds.isEmpty) return null
        val info = shapes.shape(leaf)
        return if (info.strokePx > 0f && !isTransparent(info.stroke)) leaf else null
    }

    /** The outline [foregroundOutline] found, as a node over the view's children where the foreground draws. */
    private fun foregroundNode(rec: Rec, out: ArrayList<VNode>, fg: Drawable) {
        val b = fg.bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val info = shapes.shape(fg)
        sb.setLength(0)
        sb.append("left:").appendPx(b.left * inv).append(";top:").appendPx(b.top * inv)
            .append(";width:").appendPx(w * inv).append(";height:").appendPx(h * inv).append(';')
        shapeCorners(info, w, h)
        sb.appendShadows(0f, 0, info.strokePx * inv, info.stroke)
        out.add(element(rec, FG_ROLE, null, EMPTY))
    }

    /** The background's CSS for a box of [w] x [h] pixels, with the view's elevation shadow. */
    private fun backgroundCss(v: View, rec: Rec, w: Int, h: Int) {
        val elevation = v.z * inv
        val shadow = if (elevation > 0f && v.outlineProvider != null) {
            if (Build.VERSION.SDK_INT >= 28) v.outlineSpotShadowColor else 0xFF000000.toInt()
        } else 0
        when (rec.bgKind) {
            Rec.BG_COLOR -> {
                sb.appendFill(rec.bgFill)
                sb.appendShadows(elevation, shadow, 0f, 0)
            }
            Rec.BG_UNDERLINE -> {
                val width = if (v.isFocused) 2f else 1f
                sb.append("box-shadow:inset 0 ").appendPx(-width).append(" 0 0 ").appendColor(rec.bgFill).append(';')
            }
            Rec.BG_EDGES -> {
                sb.appendFill(rec.bgFill)
                sb.append("box-shadow:")
                var first = true
                fun edge(x: Int, y: Int) {
                    if (x == 0 && y == 0) return
                    if (!first) sb.append(',')
                    first = false
                    sb.append("inset ").appendPx(x * inv).append(' ').appendPx(y * inv).append(" 0 0 ").appendColor(rec.bgEdge)
                }
                edge(rec.edgeL, 0)
                edge(0, rec.edgeT)
                edge(-rec.edgeR, 0)
                edge(0, -rec.edgeB)
                sb.append(';')
            }
            Rec.BG_SHAPE -> {
                val info = rec.bgShape ?: return
                shapeCss(info, rec.bgFill, w.toFloat(), h.toFloat(), elevation, shadow, pictured = rec.bgPictured)
            }
        }
    }

    private fun shapeCss(
        info: ShapeInfo, fill: Int, w: Float, h: Float,
        elevation: Float = 0f, shadow: Int = 0, pictured: Boolean = false,
    ) {
        if (info.image) {
            // A bitmap: its picture is a node of its own, or this placeholder tint.
            if (!pictured) sb.append("background-color:rgba(158,158,158,.22);")
        } else {
            sb.appendFill(fill)
            val colors = info.gradientColors
            if (colors != null) {
                when (info.gradientType) {
                    GradientDrawable.RADIAL_GRADIENT -> sb.appendRadialGradient(info.centerX, info.centerY, colors)
                    GradientDrawable.SWEEP_GRADIENT -> sb.appendSweepGradient(info.centerX, info.centerY, colors)
                    else -> {
                        val o = info.orientation
                        sb.appendLinearGradient(o[0], o[1], o[2], o[3], w, h, colors)
                    }
                }
            }
        }
        shapeCorners(info, w, h)
        sb.appendShadows(elevation, shadow, info.strokePx * inv, info.stroke)
    }

    /** The `border-radius` of [info]'s shape on a box of [w] x [h] pixels. */
    private fun shapeCorners(info: ShapeInfo, w: Float, h: Float) {
        if (info.oval) {
            sb.append("border-radius:50%;")
            return
        }
        val radii = info.radiiPx
        if (radii != null) {
            for (i in 0 until 8) scratchRadii[i] = min(radii[i], min(w, h) / 2) * inv
            sb.appendRadii(scratchRadii)
        } else {
            sb.appendRadius(min(info.radiusPx, min(w, h) / 2) * inv)
        }
    }

    private val scratchRadii = FloatArray(8)

    /** `overflow:hidden` with the corners of the view's outline, for a view that clips to it. */
    private fun outlineClip(v: View) {
        sb.append("overflow:hidden;")
        if (Build.VERSION.SDK_INT < 24) return
        val provider = v.outlineProvider ?: return
        try {
            outline.setEmpty()
            provider.getOutline(v, outline)
            val r = outline.radius
            if (r > 0f) sb.appendRadius(r * inv)
        } catch (_: Throwable) {
        }
    }

    // ---- helpers ----------------------------------------------------------------

    /** Where [v]'s element is in its parent element, how big, how transformed and how faded. */
    private fun geometry(v: View, rl: Float, rt: Float, w: Int, h: Int, plain: Boolean, alpha: Float) {
        sb.append("left:").appendPx(rl).append(";top:").appendPx(rt)
            .append(";width:").appendPx(w * inv).append(";height:").appendPx(h * inv).append(';')
        if (!plain) {
            v.matrix.getValues(values)
            sb.append("transform:matrix(")
                .appendNum3(values[Matrix.MSCALE_X]).append(',')
                .appendNum3(values[Matrix.MSKEW_Y]).append(',')
                .appendNum3(values[Matrix.MSKEW_X]).append(',')
                .appendNum3(values[Matrix.MSCALE_Y]).append(',')
                .appendNum1(values[Matrix.MTRANS_X] * inv).append(',')
                .appendNum1(values[Matrix.MTRANS_Y] * inv).append(");transform-origin:0 0;")
        }
        if (alpha < 0.999f) sb.append("opacity:").appendNum3(alpha).append(';')
    }

    private fun composeRoot(v: ViewGroup): Boolean {
        if (!composeChecked) {
            composeChecked = true
            composeCapture = if (ComposeSupport.available) {
                try {
                    WireframeComposeCapture(this)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }
        val c = composeCapture ?: return false
        return c.isRoot(v)
    }

    /** The theme's accent (the color of checked controls), cached per theme. */
    fun accent(v: View): Int = themeColor(v, ACCENT_ATTRS, accents)

    /** The theme's primary color (a filled button's), cached per theme. */
    fun primary(v: View): Int = themeColor(v, PRIMARY_ATTRS, primaries)

    private val primaries = WeakHashMap<Resources.Theme, Long>()

    private fun themeColor(v: View, attrs: IntArray, cache: WeakHashMap<Resources.Theme, Long>): Int {
        val theme = v.context.theme ?: return DEFAULT_PRIMARY
        // Themes are equal by their style, whatever the configuration: a day-night theme is one key in the
        // light and in the dark, so a color is kept with the night mode it was read in.
        val night = v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        cache[theme]?.let { if ((it ushr 32).toInt() == night) return it.toInt() }
        var color = DEFAULT_PRIMARY
        for (attr in attrs) {
            try {
                if (!theme.resolveAttribute(attr, typedValue, true)) continue
                val tv = typedValue
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    color = tv.data
                    break
                }
                if (tv.resourceId != 0) {
                    val csl: ColorStateList? = if (Build.VERSION.SDK_INT >= 23) {
                        v.context.resources.getColorStateList(tv.resourceId, theme)
                    } else {
                        @Suppress("DEPRECATION") v.context.resources.getColorStateList(tv.resourceId)
                    }
                    if (csl != null) {
                        color = csl.defaultColor
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
        cache[theme] = (night.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
        return color
    }

    /** Whether views of [cls] draw something of their own that the wireframe cannot read. */
    private fun drawsItself(cls: Class<*>): Boolean {
        customDraw[cls]?.let { return it }
        var c: Class<*>? = cls
        var draws = false
        while (c != null && c != View::class.java) {
            try {
                c.getDeclaredMethod("onDraw", Canvas::class.java)
                draws = true
                break
            } catch (_: NoSuchMethodException) {
            } catch (_: Throwable) {
                break
            }
            c = c.superclass
        }
        customDraw[cls] = draws
        return draws
    }

    companion object {
        const val MASK_NONE = 0
        const val MASK_BLUR = 1

        private const val BG_ROLE = 3
        private const val DIM_ROLE = 9
        private const val BG_PICTURE_ROLE = 7
        private const val FG_ROLE = 8
        private const val TOGGLE_ROLE = 4
        private const val PART_ROLE = 4
        private const val ICON_ROLE = 10
        private const val TAB_INDICATOR_ROLE = 14
        private const val LABEL_ROLE = 15
        private const val SPAN_ROLE = 20
        private const val MAX_RUNS = 60

        /** What `TextUtils.ellipsize` puts in place of the characters it cut (U+FEFF). */
        private const val ELLIPSIS_FILLER = '\uFEFF'

        private const val TOGGLE_NONE = 0
        private const val TOGGLE_CHECKBOX = 1
        private const val TOGGLE_RADIO = 2
        private const val TOGGLE_SWITCH = 3

        /** Where along its radius (a share of the view's side) a progress ring is looked for. */
        private val RING_RADII = floatArrayOf(0.46f, 0.42f, 0.38f, 0.34f)

        /** Views a capture walks at most (the iOS SDK's node budget); the rest of the screen is left out. */
        private const val MAX_VIEWS = 3000

        /** How deep a capture walks into the view tree (the iOS SDK's depth limit). */
        private const val MAX_DEPTH = 128

        /** Drawables at most this big (dp) in an ImageView are icons. */
        private const val ICON_MAX_DP = 64f

        /** How long a glyph's style stays on the page after its icon left the screen. */
        private const val GLYPH_LINGER_MS = 30_000L

        @JvmField val EMPTY: List<VNode> = emptyList()

        private val CHECKED_STATE = intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled)
        private val UNCHECKED_STATE = intArrayOf(-android.R.attr.state_checked, android.R.attr.state_enabled)
        private val PRIMARY_ATTRS = intArrayOf(android.R.attr.colorPrimary, android.R.attr.colorAccent)
        private val ACCENT_ATTRS = intArrayOf(
            android.R.attr.colorControlActivated,
            android.R.attr.colorAccent,
            android.R.attr.colorPrimary,
        )

        fun contentEquals(a: String?, b: CharSequence): Boolean {
            if (a == null || a.length != b.length) return false
            for (i in 0 until a.length) if (a[i] != b[i]) return false
            return true
        }

        fun sameChildren(a: List<VNode>, b: List<VNode>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) if (a[i] !== b[i]) return false
            return true
        }

        fun isPassword(v: TextView): Boolean {
            if (v.transformationMethod is PasswordTransformationMethod) return true
            val type = v.inputType
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            return (cls == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }

        private fun isSingleLine(v: TextView): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return v.isSingleLine
            val type = v.inputType
            return type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0 && v is EditText
        }

        private fun alignOf(alignment: Layout.Alignment?, rtl: Boolean): TextAlign = when (alignment) {
            Layout.Alignment.ALIGN_CENTER -> TextAlign.CENTER
            Layout.Alignment.ALIGN_NORMAL -> if (rtl) TextAlign.RIGHT else TextAlign.LEFT
            Layout.Alignment.ALIGN_OPPOSITE -> if (rtl) TextAlign.LEFT else TextAlign.RIGHT
            null -> TextAlign.LEFT
            // The hidden ALIGN_LEFT and ALIGN_RIGHT.
            else -> if (alignment.name.endsWith("RIGHT")) TextAlign.RIGHT else TextAlign.LEFT
        }

        /**
         * Whether [d] shows a picture (a photo, an avatar, a GIF) rather than
         * an icon or a shape: those are never drawn into glyphs.
         */
        fun pictureLike(d: Drawable, depth: Int = 0): Boolean {
            if (d is BitmapDrawable || d is NinePatchDrawable) return true
            if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) return true
            if (d is LayerDrawable && d !is RippleDrawable && depth < 3) {
                for (i in 0 until d.numberOfLayers) {
                    val layer = d.getDrawable(i)?.let { unwrap(it) } ?: continue
                    if (pictureLike(layer, depth + 1)) return true
                }
                return false
            }
            if (d is GradientDrawable || d is ColorDrawable) return false
            val name = d.javaClass.name
            if (name.contains("Bitmap") || name.contains("Gif") || name.contains("Image")) return true
            // Decoded photos are opaque; vector icons are not.
            @Suppress("DEPRECATION")
            return d.opacity == android.graphics.PixelFormat.OPAQUE
        }

        /** Content layers of a ripple: the ones that are not its mask. */
        fun contentLayers(r: RippleDrawable): Int {
            var n = 0
            for (i in 0 until r.numberOfLayers) if (r.getId(i) != android.R.id.mask) n++
            return n
        }

        /**
         * The drawable that paints what [d] shows now: the current state of a
         * selector, the content of a ripple or inset.
         */
        fun unwrap(d: Drawable): Drawable? {
            var cur: Drawable = d
            for (i in 0 until 8) {
                val next: Drawable? = when {
                    cur is DrawableContainer -> cur.current
                    cur is RippleDrawable -> {
                        if (contentLayers(cur) != 1) return cur
                        var layer: Drawable? = null
                        for (k in 0 until cur.numberOfLayers) if (cur.getId(k) != android.R.id.mask) layer = cur.getDrawable(k)
                        layer
                    }
                    cur is InsetDrawable -> cur.drawable
                    Build.VERSION.SDK_INT >= 23 && cur is DrawableWrapper -> cur.drawable
                    else -> return cur
                }
                if (next == null || next === cur) return if (cur is DrawableContainer && next == null) null else cur
                cur = next
            }
            return cur
        }
    }
}

/** What a capture keeps about one view (or Compose node) between frames. */
internal class Rec(@JvmField val id: Int) {
    /** Ids of the nodes it plays other roles with; role 0 is [id]. */
    @JvmField var ids: IntArray? = null

    /** The node made for each role last time, reused while it shows the same. */
    @JvmField var prev: Array<VNode?> = arrayOfNulls(3)

    /** Has been a node of its own: stays one, so its subtree does not move when it stops needing one. */
    @JvmField var group = false

    /** Its children are in a clipping, scrolled wrapper; sticky for the same reason. */
    @JvmField var wrap = false

    // The background found this frame, used after the children are walked.
    @JvmField var bgKind = BG_NONE
    @JvmField var bgFill = 0
    @JvmField var bgShape: ShapeInfo? = null
    @JvmField var bgL = 0
    @JvmField var bgT = 0
    @JvmField var bgR = 0
    @JvmField var bgB = 0

    /** [BG_EDGES]: the borders' color and widths (pixels). */
    @JvmField var bgEdge = 0
    @JvmField var edgeL = 0
    @JvmField var edgeT = 0
    @JvmField var edgeR = 0
    @JvmField var edgeB = 0

    /** The bitmap a bitmap background shows this frame, and whether its picture is drawn by a node of its own. */
    @JvmField var bgBitmap: android.graphics.Bitmap? = null
    @JvmField var bgPictured = false

    /** Compose only: when the node was last seen, to forget nodes that left. */
    @JvmField var seen = 0

    /** Compose: the text the style in [lookCss] was read for. Views: a text field's label. */
    @JvmField var lookText: String? = null
    @JvmField var lookCss: String? = null
    @JvmField var lookCls: String? = null
    @JvmField var lookW = 0
    @JvmField var lookH = 0

    /** Compose: the color of the text in [lookCss]. */
    @JvmField var lookColor = 0

    companion object {
        const val BG_NONE = 0
        const val BG_COLOR = 1
        const val BG_SHAPE = 2
        const val BG_UNDERLINE = 3
        const val BG_EDGES = 4
    }
}

/** The capture's children lists, reused frame after frame: last taken, first given back. */
internal class ListStack {
    private val lists = ArrayList<ArrayList<VNode>>()
    private var top = 0

    fun acquire(): ArrayList<VNode> {
        if (top == lists.size) lists.add(ArrayList(16))
        val list = lists[top++]
        list.clear()
        return list
    }

    fun release() {
        if (top > 0) lists[--top].clear()
    }

    fun reset() {
        while (top > 0) release()
    }
}

/** What a drawable paints as a box: read once per state, by drawing a few pixels of it. */
internal class ShapeInfo {
    @JvmField var key = 0L
    @JvmField var fill = 0
    @JvmField var stroke = 0
    @JvmField var strokePx = 0f
    @JvmField var radiusPx = 0f
    @JvmField var radiiPx: FloatArray? = null
    @JvmField var oval = false
    @JvmField var image = false
    @JvmField var gradientColors: IntArray? = null
    @JvmField var gradientType = 0
    @JvmField var orientation = floatArrayOf(0.5f, 0f, 0.5f, 1f)
    @JvmField var centerX = 0.5f
    @JvmField var centerY = 0.5f
    @JvmField var dominantKey = 0L
    @JvmField var dominant = 0

    val visible: Boolean
        get() = image || gradientColors != null || !isTransparent(fill) || (strokePx > 0f && !isTransparent(stroke))

    fun clear() {
        fill = 0; stroke = 0; strokePx = 0f; radiusPx = 0f; radiiPx = null; oval = false; image = false
        gradientColors = null; gradientType = 0; centerX = 0.5f; centerY = 0.5f
    }
}

/**
 * Reads colors from drawables without reflection: a drawable the wireframe
 * cannot look into (a MaterialShapeDrawable, a nine-patch) is drawn into a
 * row of a few pixels, its center giving the fill and its left edge the
 * outline. Results are kept per drawable until its bounds or state change,
 * so a drawable is drawn again only when it changed.
 */
@SuppressLint("NewApi")
internal class ShapeSampler {
    private val bitmap: Bitmap? = try {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
        null
    }
    private val canvas: Canvas? = bitmap?.let { Canvas(it) }
    private val pixels = IntArray(64)
    private val cache = WeakHashMap<Drawable, ShapeInfo>()
    private val outline = Outline()
    private val rect = Rect()

    private fun keyOf(d: Drawable): Long {
        val b = d.bounds
        var k = b.width().toLong() * 31 + b.height()
        k = k * 31 + Arrays.hashCode(d.state)
        k = k * 31 + d.level
        k = k * 31 + d.alpha
        if (Build.VERSION.SDK_INT >= 24 && d is GradientDrawable) k = k * 31 + System.identityHashCode(d.color)
        return k
    }

    private fun infoOf(d: Drawable): ShapeInfo {
        var info = cache[d]
        if (info == null) {
            info = ShapeInfo()
            info.key = Long.MIN_VALUE
            info.dominantKey = Long.MIN_VALUE
            cache[d] = info
        }
        return info
    }

    fun shape(d: Drawable): ShapeInfo {
        val info = infoOf(d)
        val key = keyOf(d)
        if (info.key == key) return info
        info.key = key
        info.clear()
        try {
            read(d, info)
        } catch (_: Throwable) {
        }
        return info
    }

    private fun read(d: Drawable, info: ShapeInfo) {
        if (d is BitmapDrawable) {
            info.image = true
            return
        }
        if (Build.VERSION.SDK_INT >= 24 && d is GradientDrawable) {
            info.oval = d.shape == GradientDrawable.OVAL
            val radii = d.cornerRadii
            if (radii != null && radii.size >= 8) info.radiiPx = radii else info.radiusPx = d.cornerRadius
            val colors = d.colors
            if (colors != null && colors.size >= 2) {
                info.gradientColors = colors
                info.gradientType = d.gradientType
                info.centerX = d.gradientCenterX
                info.centerY = d.gradientCenterY
                info.orientation = orientation(d.orientation)
            } else {
                val fill = d.color?.getColorForState(d.state, 0) ?: 0
                info.fill = withAlpha(fill, d.alpha / 255f)
            }
            sample(d, info, wantFill = false)
            return
        }
        sample(d, info, wantFill = true)
        if (Build.VERSION.SDK_INT >= 24) {
            outline.setEmpty()
            d.getOutline(outline)
            if (outline.getRect(rect)) {
                info.radiusPx = outline.radius
            } else if (!outline.isEmpty && info.visible) {
                val radii = cornerRadii(d)
                if (radii != null) {
                    if (radii.all { Math.abs(it - radii[0]) < 1f }) info.radiusPx = radii[0] else info.radiiPx = radii
                }
            }
        }
    }

    /** Draws the left edge (seven pixels) and the center of [d] at mid-height. */
    private fun sample(d: Drawable, info: ShapeInfo, wantFill: Boolean) {
        val c = canvas ?: return
        val bm = bitmap ?: return
        val b = d.bounds
        if (b.width() <= 0 || b.height() <= 0) return
        bm.eraseColor(0)
        val cy = b.exactCenterY()
        var save = c.save()
        try {
            c.clipRect(0f, 0f, 7f, 1f)
            c.translate(-b.left.toFloat(), 0.5f - cy)
            d.draw(c)
        } finally {
            c.restoreToCount(save)
        }
        save = c.save()
        try {
            c.clipRect(7f, 0f, 8f, 1f)
            c.translate(7.5f - b.exactCenterX(), 0.5f - cy)
            d.draw(c)
        } finally {
            c.restoreToCount(save)
        }
        bm.getPixels(pixels, 0, 8, 0, 0, 8, 1)
        val fill = pixels[7]
        if (wantFill) {
            info.fill = fill
        } else if (info.gradientColors == null && (fill ushr 24) >= 8 && isOutline(fill or 0xFF000000.toInt(), info.fill or 0xFF000000.toInt())) {
            // A shape's own tint (android:tint, as a framework button's colorButtonNormal) paints over its solid
            // color; only drawing it tells.
            info.fill = fill
        }
        var run = 0
        var best = 0
        var bestA = 0
        for (i in 0 until 7) {
            val p = pixels[i]
            if (!isOutline(p, fill)) break
            run = i + 1
            val a = p ushr 24
            if (a > bestA) {
                bestA = a
                best = p
            }
        }
        if (run in 1..5) {
            info.stroke = best
            info.strokePx = run.toFloat()
        }
    }

    private fun isOutline(p: Int, fill: Int): Boolean {
        if ((p ushr 24) < 48) return false
        if ((fill ushr 24) < 48) return true
        val dr = ((p shr 16) and 0xff) - ((fill shr 16) and 0xff)
        val dg = ((p shr 8) and 0xff) - ((fill shr 8) and 0xff)
        val db = (p and 0xff) - (fill and 0xff)
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db) > 48
    }

    /** The color [d] paints at ([fx], [fy]) of its bounds (fractions); 0 where it paints nothing. */
    fun pixelAt(d: Drawable, fx: Float, fy: Float): Int {
        val c = canvas ?: return 0
        val bm = bitmap ?: return 0
        val b = d.bounds
        if (b.width() <= 0 || b.height() <= 0) return 0
        bm.eraseColor(0)
        val save = c.save()
        try {
            c.clipRect(0f, 0f, 1f, 1f)
            c.translate(0.5f - (b.left + b.width() * fx), 0.5f - (b.top + b.height() * fy))
            d.draw(c)
        } catch (_: Throwable) {
            return 0
        } finally {
            c.restoreToCount(save)
        }
        return bm.getPixel(0, 0)
    }

    private val cornerBitmap: Bitmap? = try {
        Bitmap.createBitmap(CORNER_PX, CORNER_PX, Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) {
        null
    }
    private val cornerCanvas: Canvas? = cornerBitmap?.let { Canvas(it) }
    private val diagonal = IntArray(CORNER_PX)

    /**
     * The radius of each corner of [d] (pixels, top-left clockwise), for a
     * shape whose outline is not one rounded rectangle (a Material shape
     * rounded only at some corners): where the shape starts along the
     * diagonal of each corner, drawn first at a scale where the largest
     * possible radius fills [CORNER_PX] pixels, then closer around what that
     * found.
     */
    private fun cornerRadii(d: Drawable): FloatArray? {
        val b = d.bounds
        val max = min(b.width(), b.height()) / 2f
        if (max < 2f) return null
        val radii = FloatArray(8)
        for (corner in 0 until 4) {
            var s = CORNER_PX / max
            var k = crossing(d, corner, s)
            if (k < 0) return null
            if (k in 1..CORNER_PX / 4) {
                s = CORNER_PX / min(max, (k + 1) / s / CORNER_CROSSING * 1.5f)
                k = crossing(d, corner, s)
                if (k < 0) return null
            }
            val r = if (k == 0) 0f else min(max, (k + 0.5f) / s / CORNER_CROSSING)
            radii[2 * corner] = r
            radii[2 * corner + 1] = r
        }
        return radii
    }

    /** The first pixel along the diagonal of [corner] of [d], drawn at [scale], that the shape covers; -1 for none. */
    private fun crossing(d: Drawable, corner: Int, scale: Float): Int {
        val c = cornerCanvas ?: return -1
        val bm = cornerBitmap ?: return -1
        val b = d.bounds
        bm.eraseColor(0)
        val save = c.save()
        try {
            when (corner) {
                0 -> { c.scale(scale, scale); c.translate(-b.left.toFloat(), -b.top.toFloat()) }
                1 -> { c.scale(-scale, scale); c.translate(-b.right.toFloat(), -b.top.toFloat()) }
                2 -> { c.scale(-scale, -scale); c.translate(-b.right.toFloat(), -b.bottom.toFloat()) }
                else -> { c.scale(scale, -scale); c.translate(-b.left.toFloat(), -b.bottom.toFloat()) }
            }
            d.draw(c)
        } catch (_: Throwable) {
            return -1
        } finally {
            c.restoreToCount(save)
        }
        for (i in 0 until CORNER_PX) diagonal[i] = bm.getPixel(i, i)
        return diagonal.indexOfFirst { (it ushr 24) >= 128 }
    }

    /** The most opaque color [d] draws (an icon's tint), opaque; 0 when it draws nothing. */
    fun dominant(d: Drawable): Int {
        val info = infoOf(d)
        val key = keyOf(d)
        if (info.dominantKey == key) return info.dominant
        info.dominantKey = key
        info.dominant = 0
        val c = canvas ?: return 0
        val bm = bitmap ?: return 0
        val b = d.bounds
        if (b.width() <= 0 || b.height() <= 0) return 0
        bm.eraseColor(0)
        val save = c.save()
        try {
            c.scale(8f / b.width(), 8f / b.height())
            c.translate(-b.left.toFloat(), -b.top.toFloat())
            d.draw(c)
        } catch (_: Throwable) {
            return 0
        } finally {
            c.restoreToCount(save)
        }
        bm.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        var best = 0
        var bestA = 0
        for (p in pixels) {
            val a = p ushr 24
            if (a > bestA) {
                bestA = a
                best = p
            }
        }
        info.dominant = if (bestA < 24) 0 else best or 0xFF000000.toInt()
        return info.dominant
    }

    private companion object {
        /** The side of the bitmap a shape's corners are read in. */
        const val CORNER_PX = 24

        /** A quarter circle of radius r crosses its corner's diagonal r times this in from the corner (1 - 1/sqrt 2). */
        const val CORNER_CROSSING = 0.2929f
    }

    private fun orientation(o: GradientDrawable.Orientation?): FloatArray = when (o) {
        GradientDrawable.Orientation.TOP_BOTTOM -> floatArrayOf(0.5f, 0f, 0.5f, 1f)
        GradientDrawable.Orientation.TR_BL -> floatArrayOf(1f, 0f, 0f, 1f)
        GradientDrawable.Orientation.RIGHT_LEFT -> floatArrayOf(1f, 0.5f, 0f, 0.5f)
        GradientDrawable.Orientation.BR_TL -> floatArrayOf(1f, 1f, 0f, 0f)
        GradientDrawable.Orientation.BOTTOM_TOP -> floatArrayOf(0.5f, 1f, 0.5f, 0f)
        GradientDrawable.Orientation.BL_TR -> floatArrayOf(0f, 1f, 1f, 0f)
        GradientDrawable.Orientation.LEFT_RIGHT -> floatArrayOf(0f, 0.5f, 1f, 0.5f)
        GradientDrawable.Orientation.TL_BR -> floatArrayOf(0f, 0f, 1f, 1f)
        null -> floatArrayOf(0.5f, 0f, 0.5f, 1f)
    }
}
