package com.tracewayapp.traceway.interaction

private val IDENT = Regex("^(?:-?[A-Za-z_]|--)[A-Za-z0-9_-]*$")
private val TAG = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
private val UNSAFE = Regex("[:,\\\\\"\\r\\n]")

/** A quoted attribute value the dashboard accepts, or null. */
private fun quoted(value: String): String? =
    if (value.isEmpty() || UNSAFE.containsMatchIn(value)) null else "\"$value\""

/**
 * Names the element a tap landed on. The target is the view that handled it
 * (the nearest clickable one: the `MaterialButton`, the clickable row around
 * the `TextView` that was hit, the Compose node with a click action), or
 * the shown text or image when nothing handles taps there. The selector
 * prefers, in order: an id on the target, on a wrapper right around it or
 * inside it; its string tag; its text; its label; and the type alone with
 * the nearest ancestor id as context. It is checked to match [path] before
 * it is returned. Inside a privacy mask, neither text nor label is used.
 */
internal fun describeTap(path: UiElementPath, includeText: Boolean = true): TapTarget? {
    if (path.size == 0) return null
    // Selectors are made of what the app put on screen, not the window's decor around it.
    var limit = path.size
    for (i in 0 until path.size) {
        if (path[i].boundary) {
            limit = i
            break
        }
    }
    if (limit == 0) limit = path.size

    var owner = -1
    for (i in 0 until limit) {
        if (path[i].handlesTaps) {
            owner = i
            break
        }
    }
    if (owner < 0) {
        for (i in 0 until limit) {
            if (path[i].showsContent) {
                owner = i
                break
            }
        }
    }
    if (owner < 0) owner = 0

    val info = path[owner]
    val masked = path.isMasked(owner)
    val text = if (includeText && !masked) info.reportedText else null

    // An id on the target, on a wrapper right above it that holds nothing
    // else (the app's own view around a control), or inside it.
    var idIndex = -1
    val candidatesForId = ArrayList<Int>()
    candidatesForId.add(owner)
    var wrapper = owner + 1
    while (wrapper < limit && wrapper <= owner + 6 && path[wrapper].childCount == 1) candidatesForId.add(wrapper++)
    for (j in owner - 1 downTo 0) candidatesForId.add(j)
    for (i in candidatesForId) {
        if (path[i].ids.isNotEmpty()) {
            idIndex = i
            break
        }
    }

    fun tagOf(i: Int): String {
        val element = path[i]
        if (element.genericType) return "*"
        if (TAG.matches(element.type)) return element.type
        return quoted(element.type)?.let { "[type=$it]" } ?: "*"
    }

    fun withTag(tag: String, rest: String) = if (tag == "*") rest else "$tag$rest"

    val candidates = ArrayList<String>()
    var id: String? = null
    if (idIndex >= 0) {
        val first = path[idIndex].ids.first()
        id = first
        val tag = tagOf(idIndex)
        if (IDENT.matches(first)) {
            candidates.add(withTag(tag, "#$first"))
        } else {
            quoted(first)?.let { candidates.add(withTag(tag, "[identifier=$it]")) }
        }
    }
    val tag = tagOf(owner)
    val key = info.key
    if (key != null && key != id) {
        quoted(key)?.let { candidates.add(withTag(tag, "[key=$it]")) }
    }
    if (text != null && text.length <= 40) {
        val q = quoted(text)
        if (q != null) {
            candidates.add(withTag(tag, "[text=$q]"))
        } else {
            val prefix = text.split(UNSAFE).first().trim()
            if (prefix.length >= 2) candidates.add(withTag(tag, "[text^=\"$prefix\"]"))
        }
    }
    val label = if (masked) null else info.label
    if (label != null && label.length <= 60) {
        quoted(label)?.let { candidates.add(withTag(tag, "[label=$it]")) }
    }
    if (tag == "*") for (role in info.roles) candidates.add(".$role")
    for (j in owner + 1 until limit) {
        val ancestorId = path[j].ids.firstOrNull() ?: continue
        if (IDENT.matches(ancestorId)) {
            candidates.add("#$ancestorId $tag")
            break
        }
    }
    candidates.add(tag)

    var selector = tag
    for (candidate in candidates) {
        if (candidate.length > 256) continue
        val parsed = ViewSelector.parse(candidate)
        if (parsed != null && parsed.matches(path)) {
            selector = candidate
            break
        }
    }
    return TapTarget(
        selector = selector,
        tag = info.type,
        id = id,
        text = text,
        label = label,
    )
}
