package com.tracewayapp.traceway.interaction

import android.graphics.Matrix
import android.os.Build
import android.text.Spanned
import android.text.method.PasswordTransformationMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.AbsSpinner
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.tracewayapp.traceway.TracewayMaskMode
import com.tracewayapp.traceway.TracewayMasks
import java.lang.ref.WeakReference

/**
 * The views under a point of a window, as click and hover selectors see
 * them. Hit testing follows the platform's touch dispatch: children are
 * tried front to back (elevation, then child order), a child is hit when it
 * is visible (or animating) and the point, mapped through its scroll and
 * transform, is inside its bounds, and a child whose subtree does not handle
 * touches lets the ones behind it try. The deepest view hit is the target;
 * inside a Jetpack Compose view, the semantics nodes under the point
 * continue the path. Call on the main thread.
 */
internal object ViewPath {
    /**
     * The elements under ([xPx], [yPx]) in [root]'s coordinates (pixels),
     * deepest first, or null when the point is outside [root]. The path is a
     * snapshot: it keeps no reference to the views.
     */
    fun pathAt(root: View, xPx: Float, yPx: Float): UiElementPath? = hitAt(root, xPx, yPx)?.snapshot()

    /**
     * Identifies the deepest element under the point without taking a
     * snapshot: cheap enough for every hover move. Equal for the same view
     * or node; it does not retain it.
     */
    fun leafAt(root: View, xPx: Float, yPx: Float): Any? = hitAt(root, xPx, yPx)?.leaf

    /** The chain under the point, from which a snapshot is taken only when needed. */
    internal fun hitAt(root: View, xPx: Float, yPx: Float): Hit? {
        return try {
            if (xPx < 0f || yPx < 0f || xPx >= root.width || yPx >= root.height) return null
            val result = HitTester().hit(root, xPx, yPx, 0)
            if (result.chain.isEmpty()) null else Hit(result.chain)
        } catch (_: Throwable) {
            null
        }
    }

    /** The views and nodes under a point, deepest first; each one's parent follows it. */
    internal class Hit(private val chain: List<SourceNode>) {
        val leaf: Any get() = chain[0].identity()

        fun snapshot(): UiElementPath? = try {
            UiSnapshot.build(chain)
        } catch (_: Throwable) {
            null
        }
    }

    // ── Compose ──────────────────────────────────────────────────────────

    /** 0: not checked yet, 1: Compose is in the app, -1: it is not, or its API did not link. */
    @Volatile
    private var composeState = 0

    private val composeAvailable: Boolean
        get() {
            if (composeState == 0) {
                composeState = try {
                    ComposeSemantics.probe()
                    1
                } catch (_: Throwable) {
                    -1
                }
            }
            return composeState > 0
        }

    /** Runs [block] against the Compose API, or returns [fallback] when the app has no Compose or a version it cannot link to. */
    internal fun <T> withCompose(fallback: T, block: () -> T): T {
        if (!composeAvailable) return fallback
        return try {
            block()
        } catch (_: LinkageError) {
            composeState = -1
            fallback
        } catch (_: Exception) {
            fallback
        }
    }

    internal fun isComposeRoot(view: View): Boolean {
        if (!composeAvailable) return false
        return try {
            ComposeSemantics.isRoot(view)
        } catch (_: LinkageError) {
            composeState = -1
            false
        }
    }
}

/** What a snapshot reads of a view or a Compose semantics node. Implemented by tests with plain objects. */
internal abstract class SourceNode {
    /** Equal for the same view or node within one snapshot. */
    abstract val key: Any

    /** Equal for the same view or node across snapshots, without retaining it. */
    abstract fun identity(): Any

    abstract val typeFacts: TypeFacts
    abstract val ids: List<String>

    /** The string tag (`android:tag`). */
    abstract val stringKey: String?
    abstract val roles: Set<String>

    /** Its own content description or tooltip. */
    abstract val ownLabel: String?

    /**
     * The text it shows itself: a text view's text or an input's hint, never
     * what was typed. When set, its children are not read for text.
     */
    abstract val ownText: String?

    /** Not shown (gone, invisible, fully transparent): its text is not read. */
    abstract val hidden: Boolean
    abstract val masked: Boolean
    abstract val handlesTaps: Boolean
    abstract val showsContent: Boolean
    abstract val boundary: Boolean

    protected abstract fun loadChildren(): List<SourceNode>

    private var cachedChildren: List<SourceNode>? = null

    fun children(): List<SourceNode> = cachedChildren ?: loadChildren().also { cachedChildren = it }
}

/** How an element's class is named. */
internal class TypeFacts(
    val type: String,
    /** Lower-case simple names of the class and its superclasses that kept their names. */
    val tagNames: Set<String>,
    val typeNames: List<String>,
    val generic: Boolean = false,
)

/** Builds the [UiElementPath] of a hit chain. */
internal object UiSnapshot {
    const val MAX_SIBLINGS = 32
    private const val TEXT_PARTS = 3
    private const val LABEL_DEPTH = 32

    /** [chain] is deepest first, each node followed by its parent. */
    fun build(chain: List<SourceNode>): UiElementPath = Builder().build(chain)

    private class Builder {
        /** Nodes the text walks may still visit, for the whole snapshot. */
        private var budget = 3000
        private val shown = HashMap<Any, List<String>>()
        private val reported = HashMap<Any, List<String>>()

        fun build(chain: List<SourceNode>): UiElementPath {
            val elements = ArrayList<UiElement>(chain.size)
            for (i in chain.indices) {
                val node = chain[i]
                val siblings = if (i + 1 < chain.size) precedingSiblings(chain[i + 1], node) else emptyList()
                elements.add(element(node, onPath = true, siblings = siblings))
            }
            return UiElementPath(elements)
        }

        private fun precedingSiblings(parent: SourceNode, child: SourceNode): List<UiElement> {
            val children = parent.children()
            val index = children.indexOfFirst { it.key == child.key }
            if (index <= 0) return emptyList()
            val out = ArrayList<UiElement>(minOf(index, MAX_SIBLINGS))
            for (k in maxOf(0, index - MAX_SIBLINGS) until index) {
                out.add(element(children[k], onPath = false, siblings = emptyList()))
            }
            return out
        }

        private fun element(node: SourceNode, onPath: Boolean, siblings: List<UiElement>): UiElement {
            val type = node.typeFacts
            return UiElement(
                type = type.type,
                tagNames = type.tagNames,
                typeNames = type.typeNames,
                genericType = type.generic,
                ids = node.ids,
                roles = node.roles,
                key = node.stringKey,
                label = node.ownLabel ?: labelBelow(node),
                text = join(parts(node, includeMasked = true)),
                reportedText = if (onPath) join(parts(node, includeMasked = false)) else null,
                masked = node.masked,
                handlesTaps = node.handlesTaps,
                showsContent = node.showsContent,
                boundary = node.boundary,
                childCount = if (onPath) node.children().size else 0,
                precedingSiblings = siblings,
                identity = if (onPath) node.identity() else null,
            )
        }

        /**
         * The label of the first node below [node] that has one, as long as
         * the tree does not branch, and not inside a mask.
         */
        private fun labelBelow(node: SourceNode): String? {
            var current = node
            for (depth in 0 until LABEL_DEPTH) {
                val children = current.children()
                if (children.size != 1) return null
                current = children[0]
                if (current.masked) return null
                current.ownLabel?.let { return it }
            }
            return null
        }

        /**
         * The texts [node] shows: its own, else those of the nodes under it,
         * at most three. Hidden nodes are skipped, and so are masked ones
         * unless [includeMasked].
         */
        private fun parts(node: SourceNode, includeMasked: Boolean): List<String> {
            val memo = if (includeMasked) shown else reported
            memo[node.key]?.let { return it }
            if (budget-- <= 0) return emptyList()
            val own = node.ownText
            val result: List<String> = if (own != null) {
                listOfNotNull(clean(own))
            } else {
                val out = ArrayList<String>(TEXT_PARTS)
                for (child in node.children()) {
                    if (out.size >= TEXT_PARTS) break
                    if (child.hidden || (!includeMasked && child.masked)) continue
                    for (part in parts(child, includeMasked)) {
                        if (out.size >= TEXT_PARTS) break
                        out.add(part)
                    }
                }
                out
            }
            memo[node.key] = result
            return result
        }

        private fun join(parts: List<String>): String? = if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private val WHITESPACE = Regex("\\s+")

    fun clean(text: String): String? {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length > 256) collapsed.substring(0, 256) else collapsed
    }
}

/** Names classes for selectors: the simple name of the nearest class (itself or a superclass) that kept its name. */
internal object TypeNames {
    private val cache = HashMap<Class<*>, TypeFacts>()

    fun of(type: Class<*>): TypeFacts = synchronized(cache) {
        cache.getOrPut(type) { compute(type) }
    }

    private fun compute(type: Class<*>): TypeFacts {
        val tagNames = LinkedHashSet<String>()
        var readable: Class<*>? = null
        var readableName: String? = null
        var c: Class<*>? = type
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 32) {
            val simple = readableSimpleName(c)
            if (simple != null) {
                tagNames.add(simple.lowercase())
                if (readableName == null) {
                    readableName = simple
                    readable = c
                }
            }
            c = c.superclass
        }
        val runtimeSimple = simpleName(type.name)
        val name = readableName ?: runtimeSimple.ifEmpty { type.name }
        val typeNames = LinkedHashSet<String>()
        typeNames.add(type.name)
        if (runtimeSimple.isNotEmpty()) typeNames.add(runtimeSimple)
        readable?.let { typeNames.add(it.name) }
        typeNames.add(name)
        return TypeFacts(name, tagNames, typeNames.toList())
    }

    /** `com.example.Outer$Inner` → `Inner`. */
    private fun simpleName(name: String): String {
        val simple = name.substring(name.lastIndexOf('.') + 1)
        return simple.substring(simple.lastIndexOf('$') + 1)
    }

    /** Null for an anonymous or synthetic class (`Outer$1`) and for a name a minifier gave. */
    private fun readableSimpleName(type: Class<*>): String? {
        val name = type.name
        val simple = simpleName(name)
        if (simple.isEmpty() || simple[0].isDigit()) return null
        if (looksMinified(name, simple)) return null
        return simple
    }

    /**
     * R8 and ProGuard name classes `a`, `b`, ..., `aa` (lower case), in
     * packages that are renamed the same way: real classes are not named so.
     */
    private fun looksMinified(name: String, simple: String): Boolean {
        if (simple.length <= 3 && simple[0].isLowerCase()) return true
        if (simple.length > 2) return false
        val pkg = name.substring(0, maxOf(0, name.lastIndexOf('.')))
        return pkg.isEmpty() || pkg.split('.').all { it.length <= 2 }
    }
}

/** A view as an element: see [ViewSelector] for what each part maps to. */
private class ViewNode(val view: View) : SourceNode() {
    override val key: Any get() = view

    override fun identity(): Any = ViewKey(view)

    override val typeFacts: TypeFacts get() = TypeNames.of(view.javaClass)

    override val ids: List<String>
        get() = ResourceNames.of(view)?.let { listOf(it) } ?: emptyList()

    override val stringKey: String?
        get() {
            val tag = view.tag as? CharSequence ?: return null
            val text = tag.toString()
            if (text.isBlank() || text.length > 256) return null
            if (text == TracewayMasks.TAG_BLUR || text == TracewayMasks.TAG_BLANK) return null
            return text
        }

    override val roles: Set<String> get() = viewRoles(view, typeFacts)

    override val ownLabel: String?
        get() {
            view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            if (Build.VERSION.SDK_INT >= 26) {
                view.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }

    override val ownText: String?
        get() {
            val view = view
            if (view is EditText) return view.hint?.toString()
            if (view is TextView) {
                if (view.transformationMethod is PasswordTransformationMethod) return null
                return view.text?.toString()
            }
            return Hints.of(view)
        }

    override val hidden: Boolean get() = view.visibility != View.VISIBLE || view.alpha <= 0f

    override val masked: Boolean get() = TracewayMasks.own(view) != null

    override val handlesTaps: Boolean get() = viewHandlesTaps(view)

    override val showsContent: Boolean
        get() = view is ImageView || (view is TextView && ownText?.isNotBlank() == true)

    override val boundary: Boolean get() = view.id == android.R.id.content

    override fun loadChildren(): List<SourceNode> {
        val view = view
        if (ViewPath.isComposeRoot(view)) {
            return ViewPath.withCompose(emptyList()) { ComposeSemantics.rootChildren(view) }
        }
        if (view !is ViewGroup) return emptyList()
        val count = view.childCount
        val out = ArrayList<SourceNode>(count)
        for (i in 0 until count) view.getChildAt(i)?.let { out.add(ViewNode(it)) }
        return out
    }
}

internal fun viewHandlesTaps(view: View): Boolean {
    if (view.isClickable || view.isLongClickable) return true
    if (Build.VERSION.SDK_INT >= 23 && view.isContextClickable) return true
    if (view is AbsSpinner) return true
    val parent = view.parent
    return parent is AdapterView<*> && parent !is AbsSpinner
}

private fun viewRoles(view: View, type: TypeFacts): Set<String> {
    val roles = LinkedHashSet<String>(2)
    val a11y = if (Build.VERSION.SDK_INT >= 23) view.accessibilityClassName?.toString() else null
    when (a11y) {
        "android.widget.Button", "android.widget.ImageButton" -> roles.add("button")
        "android.widget.CheckBox", "android.widget.RadioButton" -> roles.add("checkbox")
        "android.widget.Switch", "android.widget.ToggleButton" -> roles.add("toggle")
        "android.widget.EditText" -> roles.add("textField")
        "android.widget.SeekBar" -> roles.add("slider")
        "android.widget.ImageView" -> roles.add("image")
    }
    when {
        view is EditText -> roles.add("textField")
        view is CompoundButton -> {
            val toggle = view is Switch || view is ToggleButton || "switchcompat" in type.tagNames
            roles.add(if (toggle) "toggle" else "checkbox")
        }
        view is Button || view is ImageButton -> roles.add("button")
        view is ImageView -> roles.add("image")
        view is CheckedTextView && view.checkMarkDrawable != null -> roles.add("checkbox")
    }
    if (view is AbsSeekBar || "baseslider" in type.tagNames) roles.add("slider")
    if (Build.VERSION.SDK_INT >= 28 && view.isAccessibilityHeading) roles.add("header")
    if (view.isSelected) roles.add("selected")
    if (view is TextView) {
        val text = view.text
        if (text is Spanned && text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()) roles.add("link")
    }
    return roles
}

/** Hints of Material's `TextInputLayout`, which draws the hint of the field inside it itself. */
private object Hints {
    private val methods = HashMap<Class<*>, java.lang.reflect.Method?>()

    fun of(view: View): String? {
        if ("textinputlayout" !in TypeNames.of(view.javaClass).tagNames) return null
        val method = synchronized(methods) {
            methods.getOrPut(view.javaClass) {
                try {
                    view.javaClass.getMethod("getHint").takeIf { CharSequence::class.java.isAssignableFrom(it.returnType) }
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return null
        return try {
            (method.invoke(view) as? CharSequence)?.toString()
        } catch (_: Exception) {
            null
        }
    }
}

/** Resource entry names of view ids (`R.id.pay` → `pay`). */
private object ResourceNames {
    private val names = HashMap<Int, String>()
    private val missing = HashSet<Int>()

    fun of(view: View): String? {
        val id = view.id
        // Ids from View.generateViewId() are below 0x01000000 and name no resource.
        if (id == View.NO_ID || id ushr 24 == 0) return null
        synchronized(this) {
            names[id]?.let { return it }
            if (id in missing) return null
        }
        val name = try {
            view.resources?.getResourceEntryName(id)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        synchronized(this) {
            if (names.size + missing.size > 2048) {
                names.clear()
                missing.clear()
            }
            if (name != null) names[id] = name else missing.add(id)
        }
        return name
    }
}

/** Identifies a view without retaining it. */
internal class ViewKey(view: Any) {
    private val ref = WeakReference(view)
    private val hash = System.identityHashCode(view)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewKey || other.hash != hash) return false
        val view = ref.get()
        return view != null && view === other.ref.get()
    }
}

/** A Compose semantics node: its owner (a view, or a [ViewKey] of it) and its id. */
private data class NodeKey(val owner: Any, val id: Int)

private class HitResult(val chain: ArrayList<SourceNode>, var consumed: Boolean)

/** One hit test; holds scratch objects so transformed views do not allocate per level. */
private class HitTester {
    private val inverse = Matrix()
    private val point = FloatArray(2)

    /** The point ([x], [y]) is in [view]'s coordinates and inside it. */
    fun hit(view: View, x: Float, y: Float, depth: Int): HitResult {
        if (ViewPath.isComposeRoot(view)) return hitCompose(view, x, y, depth)
        if (view is ViewGroup && depth < MAX_DEPTH) {
            var fallback: HitResult? = null
            val order = touchOrder(view)
            for (k in order.indices) {
                val child = view.getChildAt(order[k]) ?: continue
                val local = childHit(view, child, x, y, depth) ?: continue
                if (local.consumed) {
                    local.chain.add(ViewNode(view))
                    return local
                }
                // A transparent view the touch passes through is not what the user saw.
                if (fallback == null && child.alpha > 0f) fallback = local
            }
            val result = fallback ?: HitResult(ArrayList(), false)
            result.chain.add(ViewNode(view))
            result.consumed = viewHandlesTaps(view)
            return result
        }
        return HitResult(arrayListOf(ViewNode(view)), viewHandlesTaps(view))
    }

    /** A Compose view: the semantics nodes under the point, and the `AndroidView`s it hosts. */
    private fun hitCompose(view: View, x: Float, y: Float, depth: Int): HitResult {
        val compose = ViewPath.withCompose<ComposeHit?>(null) { ComposeSemantics.hit(view, x, y) }
        var interop: HitResult? = null
        if (view is ViewGroup && depth < MAX_DEPTH) {
            val order = touchOrder(view)
            for (k in order.indices) {
                val child = view.getChildAt(order[k]) ?: continue
                val local = childHit(view, child, x, y, depth) ?: continue
                // The host of the AndroidViews is as big as the Compose view; only a view inside it counts.
                if (local.chain.size > 1 && local.consumed) {
                    interop = local
                    break
                }
            }
        }
        val chain = ArrayList<SourceNode>()
        interop?.let { chain.addAll(it.chain.subList(0, it.chain.size - 1)) }
        compose?.let { chain.addAll(it.chain) }
        chain.add(ViewNode(view))
        val consumed = interop != null || compose?.consumed == true || viewHandlesTaps(view)
        return HitResult(chain, consumed)
    }

    private fun childHit(parent: ViewGroup, child: View, x: Float, y: Float, depth: Int): HitResult? {
        if (child.visibility != View.VISIBLE && child.animation == null) return null
        var localX = x + parent.scrollX - child.left
        var localY = y + parent.scrollY - child.top
        val matrix = child.matrix
        if (!matrix.isIdentity) {
            if (!matrix.invert(inverse)) return null
            point[0] = localX
            point[1] = localY
            inverse.mapPoints(point)
            localX = point[0]
            localY = point[1]
        }
        if (localX < 0f || localY < 0f || localX >= child.width || localY >= child.height) return null
        return hit(child, localX, localY, depth + 1)
    }

    /** Child indices front to back: higher elevation first, then later children. */
    private fun touchOrder(group: ViewGroup): IntArray {
        val count = group.childCount
        val order = IntArray(count) { count - 1 - it }
        var elevated = false
        for (i in 0 until count) {
            if ((group.getChildAt(i)?.z ?: 0f) != 0f) {
                elevated = true
                break
            }
        }
        if (!elevated) return order
        val boxed = order.toTypedArray()
        // Stable: equal elevations keep the later child in front.
        java.util.Arrays.sort(boxed) { a, b -> (group.getChildAt(b)?.z ?: 0f).compareTo(group.getChildAt(a)?.z ?: 0f) }
        return boxed.toIntArray()
    }

    private companion object {
        const val MAX_DEPTH = 256
    }
}

internal class ComposeHit(val chain: List<SourceNode>, val consumed: Boolean)

/**
 * Jetpack Compose semantics, read through `RootForTest`, which every
 * `AndroidComposeView` implements. Loaded only after [ViewPath] found
 * Compose in the app; any linkage failure turns Compose support off.
 */
private object ComposeSemantics {
    /** Throws [NoClassDefFoundError] when the app has no Compose. A class literal, so it survives minification. */
    fun probe(): Class<*> = RootForTest::class.java

    fun isRoot(view: View): Boolean = view is RootForTest

    private fun root(view: View): SemanticsNode = (view as RootForTest).semanticsOwner.unmergedRootSemanticsNode

    fun rootChildren(view: View): List<SourceNode> = root(view).children.map { ComposeNode(it, view) }

    /** The nodes under ([x], [y]) (pixels in [view]), deepest first, without the root. */
    fun hit(view: View, x: Float, y: Float): ComposeHit? {
        val result = descend(root(view), view, x, y, 0)
        if (result.chain.isEmpty()) return null
        return ComposeHit(result.chain, result.consumed)
    }

    private fun descend(node: SemanticsNode, owner: View, x: Float, y: Float, depth: Int): HitResult {
        var fallback: HitResult? = null
        if (depth < 256) {
            // Children are in z order: the last one is drawn on top.
            val children = node.children
            for (k in children.indices.reversed()) {
                val child = children[k]
                if (!child.layoutInfo.isPlaced) continue
                val bounds = child.boundsInRoot
                if (x < bounds.left || y < bounds.top || x >= bounds.right || y >= bounds.bottom) continue
                val sub = descend(child, owner, x, y, depth + 1)
                if (sub.consumed) return sub
                if (fallback == null && !ComposeNode(child, owner).hidden) fallback = sub
            }
        }
        val result = fallback ?: HitResult(ArrayList(), false)
        if (!node.isRoot) {
            val element = ComposeNode(node, owner)
            result.chain.add(element)
            result.consumed = element.handlesTaps
        }
        return result
    }
}

/** A Compose semantics node (unmerged) as an element. */
private class ComposeNode(private val node: SemanticsNode, private val owner: View) : SourceNode() {
    private val config = node.config

    override val key: Any = NodeKey(owner, node.id)

    override fun identity(): Any = NodeKey(ViewKey(owner), node.id)

    private val role: String? by lazy(LazyThreadSafetyMode.NONE) {
        config.getOrNull(SemanticsProperties.Role)?.toString()?.takeIf { it != "Unknown" && it.isNotEmpty() && it[0].isLetter() }
    }

    private val editable: Boolean
        get() = SemanticsProperties.EditableText in config || SemanticsActions.SetText in config

    override val typeFacts: TypeFacts
        get() {
            val progress = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            val type = role ?: when {
                editable -> "TextField"
                progress != null -> if (SemanticsActions.SetProgress in config) "Slider" else "ProgressIndicator"
                SemanticsProperties.Text in config -> "Text"
                else -> GENERIC
            }
            return TypeFacts(type, setOf(type.lowercase()), listOf(type), generic = type == GENERIC)
        }

    /** The test tag, unless it is a mask tag (`traceway-mask`, `traceway-blank`): that one masks, it names nothing. */
    override val ids: List<String>
        get() = config.getOrNull(SemanticsProperties.TestTag)
            ?.takeIf { it.isNotEmpty() && !isMaskTag(it) }
            ?.let { listOf(it) } ?: emptyList()

    override val stringKey: String? get() = null

    override val roles: Set<String>
        get() {
            val roles = LinkedHashSet<String>(2)
            when (role) {
                "Button", "DropdownList" -> roles.add("button")
                "Checkbox", "RadioButton" -> roles.add("checkbox")
                "Switch" -> roles.add("toggle")
                "Image" -> roles.add("image")
            }
            if (role == null && SemanticsProperties.ToggleableState in config) roles.add("checkbox")
            if (editable) roles.add("textField")
            if (SemanticsProperties.Heading in config) roles.add("header")
            if (SemanticsActions.SetProgress in config && SemanticsProperties.ProgressBarRangeInfo in config) roles.add("slider")
            if (config.getOrNull(SemanticsProperties.Selected) == true) roles.add("selected")
            return roles
        }

    override val ownLabel: String?
        get() = config.getOrNull(SemanticsProperties.ContentDescription)
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }

    override val ownText: String?
        get() {
            if (editable || SemanticsProperties.Password in config) return null
            return config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        }

    override val hidden: Boolean
        get() {
            if (!node.layoutInfo.isPlaced) return true
            // By name: InvisibleToUser is experimental, HideFromAccessibility newer than the version built against.
            for (entry in config) {
                val name = entry.key.name
                if (name == "InvisibleToUser" || name == "HideFromAccessibility") return true
            }
            return false
        }

    /**
     * A Compose mask is a semantics property whose value is a
     * [TracewayMaskMode] (`Modifier.tracewayMask`), or a mask test
     * tag, as the wireframe reads them.
     */
    override val masked: Boolean
        get() {
            for (entry in config) if (entry.value is TracewayMaskMode) return true
            return config.getOrNull(SemanticsProperties.TestTag)?.let(::isMaskTag) == true
        }

    override val handlesTaps: Boolean
        get() = SemanticsActions.OnClick in config || SemanticsActions.OnLongClick in config

    override val showsContent: Boolean get() = ownText != null || role == "Image"

    override val boundary: Boolean get() = false

    override fun loadChildren(): List<SourceNode> = node.children.map { ComposeNode(it, owner) }

    private companion object {
        const val GENERIC = "Composable"

        fun isMaskTag(tag: String): Boolean = tag == TracewayMasks.TAG_BLUR || tag == TracewayMasks.TAG_BLANK
    }
}
