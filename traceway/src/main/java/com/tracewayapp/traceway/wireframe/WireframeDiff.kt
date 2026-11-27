package com.tracewayapp.traceway.wireframe

/**
 * The data of an rrweb mutation event (`IncrementalSource.Mutation`) that
 * turns the screen [before] into [after], or null when both show the same.
 * Both trees are rooted at the same node.
 *
 * rrweb's replayer applies the removes first, then the adds, then text and
 * attribute changes. An add builds only its own node, never the node's
 * children, so a new subtree is added node by node, parent first, and
 * siblings from the last to the first: each add's `nextId` names a node
 * that is already on the page. A node that moved, to another parent or
 * among its siblings, is removed and added again with its whole subtree.
 */
internal fun diffScreens(before: VNode, after: VNode): Map<String, Any?>? {
    if (before === after) return null
    val diff = ScreenDiff()
    diff.node(before, after)
    if (diff.isEmpty) return null
    return linkedMapOf(
        "source" to 0,
        "texts" to diff.texts,
        "attributes" to diff.attributes,
        "removes" to diff.removes,
        "adds" to diff.adds,
    )
}

private class ScreenDiff {
    val texts = ArrayList<Map<String, Any?>>()
    val attributes = ArrayList<Map<String, Any?>>()
    val removes = ArrayList<Map<String, Any?>>()
    val adds = ArrayList<Map<String, Any?>>()

    val isEmpty: Boolean
        get() = texts.isEmpty() && attributes.isEmpty() && removes.isEmpty() && adds.isEmpty()

    /** [before] and [after] are the same node (same id and kind) in the two frames. */
    fun node(before: VNode, after: VNode) {
        if (before === after) return
        if (after.isText) {
            if (before.text != after.text) texts.add(linkedMapOf("id" to after.id, "value" to after.text))
            return
        }
        attributes(before, after)
        children(before, after)
    }

    private fun attributes(before: VNode, after: VNode) {
        var changed: LinkedHashMap<String, String?>? = null
        for ((key, value) in after.attributes) {
            if (before.attributes[key] != value) {
                if (changed == null) changed = LinkedHashMap()
                changed[key] = value
            }
        }
        for (key in before.attributes.keys) {
            if (!after.attributes.containsKey(key)) {
                if (changed == null) changed = LinkedHashMap()
                changed[key] = null
            }
        }
        if (changed != null) attributes.add(linkedMapOf("id" to after.id, "attributes" to changed))
    }

    private fun children(before: VNode, after: VNode) {
        val oldChildren = before.children
        val newChildren = after.children
        if (oldChildren === newChildren) return

        val oldIndex = HashMap<Int, Int>(oldChildren.size * 2)
        for (i in oldChildren.indices) oldIndex[oldChildren[i].id] = i
        // The children that are still here, as their old positions in their
        // new order. The longest increasing run of those stays in place;
        // every other one moved.
        val keptNew = ArrayList<Int>()
        val keptOld = ArrayList<Int>()
        for (i in newChildren.indices) {
            val at = oldIndex[newChildren[i].id] ?: continue
            if (oldChildren[at].tag == newChildren[i].tag) {
                keptNew.add(i)
                keptOld.add(at)
            }
        }
        val inPlace = HashSet<Int>()
        for (k in longestIncreasing(keptOld)) inPlace.add(newChildren[keptNew[k]].id)

        for (child in oldChildren) {
            if (child.id !in inPlace) removes.add(linkedMapOf("parentId" to before.id, "id" to child.id))
        }
        for (child in newChildren) {
            if (child.id in inPlace) node(oldChildren[oldIndex.getValue(child.id)], child)
        }
        for (i in newChildren.indices.reversed()) {
            val child = newChildren[i]
            if (child.id in inPlace) continue
            add(child, after.id, if (i + 1 < newChildren.size) newChildren[i + 1].id else null)
        }
    }

    private fun add(node: VNode, parentId: Int, nextId: Int?) {
        adds.add(linkedMapOf("parentId" to parentId, "nextId" to nextId, "node" to node.serialize(withChildren = false)))
        val children = node.children
        for (i in children.indices.reversed()) {
            add(children[i], node.id, if (i + 1 < children.size) children[i + 1].id else null)
        }
    }
}

/** Positions in [values] of one longest strictly increasing subsequence. */
internal fun longestIncreasing(values: List<Int>): IntArray {
    if (values.isEmpty()) return IntArray(0)
    // tails[k]: position of the smallest last value of a run of length k + 1.
    val tails = IntArray(values.size)
    var length = 0
    val previous = IntArray(values.size) { -1 }
    for (i in values.indices) {
        var lo = 0
        var hi = length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[tails[mid]] < values[i]) lo = mid + 1 else hi = mid
        }
        if (lo > 0) previous[i] = tails[lo - 1]
        tails[lo] = i
        if (lo == length) length++
    }
    val out = IntArray(length)
    var k = tails[length - 1]
    for (i in length - 1 downTo 0) {
        out[i] = k
        k = previous[k]
    }
    return out
}
