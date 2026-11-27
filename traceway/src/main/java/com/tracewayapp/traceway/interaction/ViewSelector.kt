package com.tracewayapp.traceway.interaction

/**
 * How the SDK names a view for click and hover triggers, with the CSS
 * selector syntax the dashboard already accepts for the browser SDK. Android
 * has no DOM, so a selector runs over the views (and Jetpack Compose
 * semantics nodes) under the pointer:
 *
 * | Selector | Matches an element that |
 * |---|---|
 * | `MaterialButton` | is of that class or a subclass of it (simple name, any case): `Button` matches a `MaterialButton` |
 * | `#pay` | has the id `R.id.pay` (its resource entry name), or `Modifier.testTag("pay")` |
 * | `.button` | has that role: `button`, `link`, `header`, `image`, `textField`, `slider`, `checkbox`, `toggle`, `selected` |
 * | `[key="42"]` | has the string tag `42` (`android:tag`), or that id or test tag |
 * | `[identifier="..."]` | the same values as `key` |
 * | `[label="Delete"]` | content description or tooltip: its own, else the first one below it along single children |
 * | `[text="Pay"]` | shown text: a `TextView`'s own (an input's hint, never its content), or that of the views under it |
 * | `[type="..."]` | full or simple class name, for a class that cannot be written as a tag |
 *
 * Attribute values support `=`, `~=`, `|=`, `^=`, `$=` and `*=`. A
 * descendant (space), child (`>`), adjacent (`+`) or general (`~`) sibling
 * combinator relates elements of the view tree. A tap matches a selector
 * when the element under the finger or one of its ancestors does, as a
 * browser click matches its target or an ancestor.
 *
 * Class names are renamed in minified builds; a renamed class is named by
 * its nearest superclass that kept its name. Ids, test tags, roles, labels
 * and text keep working there.
 */
internal class ViewSelector private constructor(
    private val compounds: List<Compound>,
    /** `combinators[i]` joins `compounds[i]` and `compounds[i + 1]`. */
    private val combinators: List<Char>,
) {
    /** Index of the deepest element of [path] the selector matches, or null. */
    fun firstMatch(path: UiElementPath): Int? = firstMatchWhere(path) { true }

    fun matches(path: UiElementPath): Boolean = firstMatch(path) != null

    /** Like [firstMatch], among the elements [accept] allows. */
    fun firstMatchWhere(path: UiElementPath, accept: (UiElement) -> Boolean): Int? {
        val budget = Budget()
        for (i in 0 until path.size) {
            if (accept(path[i]) && matchesNode(path, path[i], i + 1, budget)) return i
            if (budget.exhausted) return null
        }
        return null
    }

    private fun matchesNode(path: UiElementPath, element: UiElement, parentIndex: Int, budget: Budget): Boolean {
        val last = compounds.size - 1
        return compounds[last].test(element, budget) && matchLeft(path, last, element, parentIndex, budget)
    }

    /**
     * [element] matched compound [ci]; checks the compounds to its left.
     * [parentIndex] is where [element]'s ancestors start in [path].
     */
    private fun matchLeft(path: UiElementPath, ci: Int, element: UiElement, parentIndex: Int, budget: Budget): Boolean {
        if (ci == 0) return true
        if (budget.exhausted) return false
        val left = compounds[ci - 1]
        when (val combinator = combinators[ci - 1]) {
            '>' -> {
                if (parentIndex >= path.size) return false
                val parent = path[parentIndex]
                return left.test(parent, budget) && matchLeft(path, ci - 1, parent, parentIndex + 1, budget)
            }
            '+', '~' -> {
                if (parentIndex >= path.size) return false
                val siblings = path.childrenUpTo(parentIndex)
                val index = siblings.indexOfFirst { it === element }
                if (index <= 0) return false
                val stop = if (combinator == '+') index - 1 else 0
                for (k in index - 1 downTo stop) {
                    val sibling = siblings[k]
                    if (left.test(sibling, budget) && matchLeft(path, ci - 1, sibling, parentIndex, budget)) return true
                }
                return false
            }
            else -> {
                for (j in parentIndex until path.size) {
                    val ancestor = path[j]
                    if (left.test(ancestor, budget) && matchLeft(path, ci - 1, ancestor, j + 1, budget)) return true
                    if (budget.exhausted) return false
                }
                return false
            }
        }
    }

    companion object {
        private val cache = LinkedHashMap<String, ViewSelector?>()

        /**
         * Parses [text], or returns null for a selector this SDK cannot run.
         * Pseudo-classes, selector lists and escapes are not supported, the
         * same as for the browser SDK.
         */
        fun parse(text: String): ViewSelector? {
            synchronized(cache) {
                if (cache.containsKey(text)) return cache[text]
            }
            val parsed = try {
                Parser(text).parse()?.let { (compounds, combinators) -> ViewSelector(compounds, combinators) }
            } catch (_: Exception) {
                null
            }
            synchronized(cache) {
                if (cache.size > 200) cache.clear()
                cache[text] = parsed
            }
            return parsed
        }
    }
}

/**
 * Bounds the work of one match, so a selector with many descendant
 * combinators cannot stall a tap on a deep tree.
 */
private class Budget {
    var remaining = 20000
    val exhausted: Boolean get() = remaining <= 0
}

private val WHITESPACE = Regex("\\s+")

private class AttributeTest(val name: String, val op: String?, val value: String?) {
    /** Passes when one of the element's [values] for the attribute does. */
    fun test(values: List<String>): Boolean {
        if (values.isEmpty()) return false
        if (op == null || value == null) return true
        return values.any { test(it, value) }
    }

    private fun test(actual: String, value: String): Boolean = when (op) {
        "=" -> actual == value
        "~=" -> value.isNotEmpty() && actual.split(WHITESPACE).contains(value)
        "|=" -> actual == value || actual.startsWith("$value-")
        "^=" -> value.isNotEmpty() && actual.startsWith(value)
        "$=" -> value.isNotEmpty() && actual.endsWith(value)
        "*=" -> value.isNotEmpty() && actual.contains(value)
        else -> false
    }
}

private class Compound {
    var tag: String? = null
    val ids = ArrayList<String>(1)
    val classes = ArrayList<String>(1)
    val attributes = ArrayList<AttributeTest>(1)

    val isEmpty: Boolean get() = tag == null && ids.isEmpty() && classes.isEmpty() && attributes.isEmpty()

    fun test(element: UiElement, budget: Budget): Boolean {
        budget.remaining--
        val tag = tag
        if (tag != null && tag != "*" && tag.lowercase() !in element.tagNames) return false
        for (id in ids) if (id !in element.ids) return false
        for (role in classes) if (role !in element.roles) return false
        for (attribute in attributes) if (!attribute.test(element.attribute(attribute.name))) return false
        return true
    }
}

private class Parser(private val text: String) {
    private var i = 0
    private val done: Boolean get() = i >= text.length
    private val char: Char get() = text[i]

    private fun skipSpace() {
        while (!done && isSpace(char)) i++
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun isLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'

    private fun isNameChar(c: Char) = isLetter(c) || c in '0'..'9' || c == '_' || c == '-'

    /** `(?:-?[A-Za-z_]|--)[A-Za-z0-9_-]*` */
    private fun readIdent(): String? {
        val start = i
        var j = i
        if (j + 1 < text.length && text[j] == '-' && text[j + 1] == '-') {
            j += 2
        } else {
            if (j < text.length && text[j] == '-') j++
            if (j < text.length && (isLetter(text[j]) || text[j] == '_')) j++ else return null
        }
        while (j < text.length && isNameChar(text[j])) j++
        i = j
        return text.substring(start, j)
    }

    /** `[A-Za-z][A-Za-z0-9_-]*` */
    private fun readTag(): String? {
        if (done || !isLetter(char)) return null
        val start = i
        i++
        while (!done && isNameChar(char)) i++
        return text.substring(start, i)
    }

    /** The compounds and the combinators between them, or null when [text] is not a selector this SDK runs. */
    fun parse(): Pair<List<Compound>, List<Char>>? {
        if (text.length > 256 || text.any { it == ':' || it == ',' || it == '\\' }) return null
        val compounds = ArrayList<Compound>()
        val combinators = ArrayList<Char>()
        skipSpace()
        while (true) {
            val compound = readCompound() ?: return null
            compounds.add(compound)
            val before = i
            skipSpace()
            if (done) break
            if (char == '>' || char == '+' || char == '~') {
                combinators.add(char)
                i++
                skipSpace()
            } else if (i > before) {
                combinators.add(' ')
            } else {
                return null
            }
        }
        if (compounds.isEmpty()) return null
        return compounds to combinators
    }

    private fun readCompound(): Compound? {
        val compound = Compound()
        if (!done && char == '*') {
            compound.tag = "*"
            i++
        } else {
            compound.tag = readTag()
        }
        while (!done) {
            val c = char
            if (c == '#' || c == '.') {
                i++
                val name = readIdent() ?: return null
                (if (c == '#') compound.ids else compound.classes).add(name)
            } else if (c == '[') {
                i++
                compound.attributes.add(readAttribute() ?: return null)
            } else {
                break
            }
        }
        return if (compound.isEmpty) null else compound
    }

    private fun readAttribute(): AttributeTest? {
        skipSpace()
        val name = readIdent() ?: return null
        skipSpace()
        if (done) return null
        if (char == ']') {
            i++
            return AttributeTest(name, null, null)
        }
        val op: String
        if (char == '=') {
            op = "="
            i++
        } else if (i + 1 < text.length && char in "~|^$*" && text[i + 1] == '=') {
            op = "$char="
            i += 2
        } else {
            return null
        }
        skipSpace()
        if (done) return null
        val value: String
        if (char == '"' || char == '\'') {
            val end = text.indexOf(char, i + 1)
            if (end < 0) return null
            value = text.substring(i + 1, end)
            if (value.contains('\n') || value.contains('\r')) return null
            i = end + 1
        } else {
            value = readIdent() ?: return null
        }
        skipSpace()
        if (done || char != ']') return null
        i++
        return AttributeTest(name, op, value)
    }
}

/**
 * One view or Compose semantics node of a [UiElementPath], as click and
 * hover selectors see it. A snapshot: it keeps no reference to the view, so
 * a path can outlive the screen it was taken on.
 */
internal class UiElement(
    /** The name a recorded selector uses: the simple class name, or the Compose role or kind (`Button`, `Text`). */
    val type: String,
    /** Names a tag selector matches ([type] and its superclasses' simple names); stored in lower case. */
    tagNames: Collection<String> = listOf(type),
    /** Values of `[type=]`: the full and simple class names. */
    val typeNames: List<String> = listOf(type),
    /** [type] is only a placeholder (a Compose node without a role or text): recorded selectors leave it out. */
    val genericType: Boolean = false,
    /** The view's resource entry name or the node's test tag. */
    val ids: List<String> = emptyList(),
    val roles: Set<String> = emptySet(),
    /** The view's string tag (`android:tag`). */
    val key: String? = null,
    /** Content description or tooltip: its own, else the first one below it along single children. */
    val label: String? = null,
    /** The text shown by it or the views under it, masked parts included; never the content of an input. */
    val text: String? = null,
    /** [text] without the parts inside privacy masks: what a tap may report. */
    val reportedText: String? = text,
    /** A privacy mask is set on this element itself. */
    val masked: Boolean = false,
    /** Clickable, long-clickable or an adapter view item: it handles the taps that land on it. */
    val handlesTaps: Boolean = false,
    /** It shows text or an image itself: a tap that nothing handles is named after it. */
    val showsContent: Boolean = false,
    /** The window's content root (`android.R.id.content`): recorded selectors are made of what is below it. */
    val boundary: Boolean = false,
    val childCount: Int = 0,
    /** The siblings before this element in its parent, in child order (at most the 32 nearest). Path elements only. */
    val precedingSiblings: List<UiElement> = emptyList(),
    /** Equal for the same view or node across snapshots, without retaining it: hover steps compare paths with it. */
    val identity: Any? = null,
) {
    val tagNames: Set<String> = tagNames.mapTo(HashSet()) { it.lowercase() }.apply { add(type.lowercase()) }

    /** The element's values for `[name]`; empty when it has none. */
    fun attribute(name: String): List<String> = when (name) {
        "text" -> listOfNotNull(text)
        "type" -> typeNames
        "id" -> ids.take(1)
        "label" -> listOfNotNull(label)
        "key", "identifier" -> if (key == null) ids else (listOf(key) + ids).distinct()
        else -> emptyList()
    }

    override fun toString(): String = buildString {
        append(type)
        for (id in ids) append('#').append(id)
        for (role in roles) append('.').append(role)
        if (text != null) append("[text=\"").append(text).append("\"]")
    }
}

/** The element under the pointer and its ancestors, deepest first. */
internal class UiElementPath(val elements: List<UiElement>) : ElementPath {
    val size: Int get() = elements.size

    operator fun get(index: Int): UiElement = elements[index]

    private val identities: Set<Any> by lazy { elements.mapNotNullTo(HashSet()) { it.identity } }

    /** The children of the element at [parentIndex] up to the one on the path, in child order. */
    fun childrenUpTo(parentIndex: Int): List<UiElement> {
        val child = elements[parentIndex - 1]
        return child.precedingSiblings + child
    }

    /** Whether the element at [index] or one of its ancestors is inside a privacy mask. */
    fun isMasked(index: Int): Boolean {
        for (i in index until elements.size) if (elements[i].masked) return true
        return false
    }

    /** Whether [element] (by identity) is on this path. */
    fun contains(element: UiElement): Boolean {
        val identity = element.identity ?: return false
        return identity in identities
    }

    override fun matches(selector: String): Boolean = ViewSelector.parse(selector)?.matches(this) ?: false

    override fun matchesEntering(selector: String, previous: ElementPath?): Boolean {
        val parsed = ViewSelector.parse(selector) ?: return false
        val before = previous as? UiElementPath ?: return parsed.matches(this)
        return parsed.firstMatchWhere(this) { !before.contains(it) } != null
    }

    override fun toString(): String = elements.joinToString(" < ")
}
