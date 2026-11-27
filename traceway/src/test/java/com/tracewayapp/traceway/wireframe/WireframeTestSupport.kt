package com.tracewayapp.traceway.wireframe

import com.tracewayapp.traceway.internal.Cancellable
import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.util.Random

/**
 * What rrweb's replayer does with a page, closely enough to tell whether
 * our events rebuild the screen: a mirror of ids to nodes, a full snapshot
 * builds the whole tree, a mutation applies removes, then adds (each built
 * without its children and inserted before `nextId`), then texts and
 * attributes. Stricter than rrweb: an add whose parent or next sibling is
 * not on the page yet fails instead of waiting in a queue, and an id that
 * is already on the page fails instead of orphaning the old node.
 *
 * Events go through JSON first, as they reach the dashboard.
 */
internal class Mirror {
    class DomNode(val id: Int, val tag: String?, var text: String?, val attributes: MutableMap<String, String>) {
        val children = ArrayList<DomNode>()
        var parent: DomNode? = null

        fun dump(): String = if (tag == null) {
            "#$id\"$text\""
        } else {
            "#$id<$tag ${attributes.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }}>" +
                "[${children.joinToString(",") { it.dump() }}]"
        }
    }

    val byId = HashMap<Int, DomNode>()
    var document: DomNode? = null

    private fun int(v: Any?): Int = (v as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    private fun map(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    fun fullSnapshot(node: Map<String, Any?>) {
        byId.clear()
        document = build(node, withChildren = true)
    }

    private fun build(n: Map<String, Any?>, withChildren: Boolean): DomNode {
        val id = int(n["id"])
        assertFalse("id $id added twice", byId.containsKey(id))
        val type = int(n["type"])
        val attributes = LinkedHashMap<String, String>()
        if (type == 2) map(n["attributes"]).forEach { (k, v) -> attributes[k] = v as String }
        val node = DomNode(
            id,
            if (type == 2) n["tagName"] as String else null,
            if (type == 3) n["textContent"] as String else null,
            attributes,
        )
        byId[id] = node
        if (withChildren) {
            for (child in (n["childNodes"] as List<*>?).orEmpty()) {
                val built = build(map(child), withChildren = true)
                built.parent = node
                node.children.add(built)
            }
        }
        return node
    }

    private fun unmap(node: DomNode) {
        byId.remove(node.id)
        for (c in node.children) unmap(c)
    }

    fun mutation(data: Map<String, Any?>) {
        assertEquals(0, int(data["source"]))
        for (key in listOf("removes", "adds", "texts", "attributes")) {
            assertTrue("$key must be a list", data[key] is List<*>)
        }
        for (r in data["removes"] as List<*>) {
            val m = map(r)
            val node = byId[int(m["id"])]
            val parent = byId[int(m["parentId"])]
            assertNotNull("remove of missing ${m["id"]}", node)
            assertNotNull("remove from missing ${m["parentId"]}", parent)
            assertTrue("${m["id"]} is not a child of ${m["parentId"]}", parent!!.children.contains(node))
            parent.children.remove(node)
            unmap(node!!)
        }
        for (a in data["adds"] as List<*>) {
            val m = map(a)
            val parent = byId[int(m["parentId"])]
            assertNotNull("add under missing ${m["parentId"]}", parent)
            val nextId = (m["nextId"] as Number?)?.toInt()
            val node = build(map(m["node"]), withChildren = false)
            node.parent = parent
            if (nextId == null) {
                parent!!.children.add(node)
            } else {
                val next = byId[nextId]
                assertNotNull("add before missing $nextId", next)
                val at = parent!!.children.indexOf(next)
                assertTrue("$nextId is not a child of ${m["parentId"]}", at >= 0)
                parent.children.add(at, node)
            }
        }
        for (t in data["texts"] as List<*>) {
            val m = map(t)
            val node = byId[int(m["id"])]
            assertNotNull(node)
            assertEquals("text change of a non-text node", null, node!!.tag)
            node.text = m["value"] as String
        }
        for (a in data["attributes"] as List<*>) {
            val m = map(a)
            val node = byId[int(m["id"])]
            assertNotNull(node)
            map(m["attributes"]).forEach { (k, v) ->
                if (v == null) node!!.attributes.remove(k) else node!!.attributes[k] = v as String
            }
        }
    }

    /** Plays [events] in order. */
    fun play(events: List<Map<String, Any?>>) {
        @Suppress("UNCHECKED_CAST")
        val parsed = Json.parse(Json.stringify(events)) as List<Map<String, Any?>>
        for (event in parsed) {
            when (int(event["type"])) {
                2 -> fullSnapshot(map(map(event["data"])["node"]))
                3 -> {
                    val data = map(event["data"])
                    when (int(data["source"])) {
                        0 -> mutation(data)
                        2 -> assertTrue("interaction with a node not on the page", byId.containsKey(int(data["id"])))
                    }
                }
            }
        }
    }

    val screen: DomNode get() = byId[screenNodeId]!!

    fun texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: DomNode) {
            n.text?.let { out.add(it) }
            n.children.forEach(::walk)
        }
        walk(screen)
        return out
    }
}

internal fun dumpVNode(n: VNode): String = if (n.isText) {
    "#${n.id}\"${n.text}\""
} else {
    "#${n.id}<${n.tag} ${n.attributes.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }}>" +
        "[${n.children.joinToString(",") { dumpVNode(it) }}]"
}

internal fun allNodes(n: VNode): Sequence<VNode> = sequence {
    yield(n)
    for (c in n.children) yieldAll(allNodes(c))
}

internal fun fullSnapshotOf(screen: VNode): Map<String, Any?> =
    mapOf("type" to 2, "data" to mapOf("node" to serializeDocument(screen)), "timestamp" to 0L)

/** Random screens and random edits of them, which share what the edit does not touch. */
internal class TreeGen(private val random: Random) {
    var nextId = 100

    fun node(depth: Int): VNode {
        if (depth > 0 && random.nextInt(4) == 0) return VNode.text(nextId++, "text${random.nextInt(50)}")
        val children = if (depth >= 4) emptyList() else List(random.nextInt(4)) { node(depth + 1) }
        return VNode.element(nextId++, TAGS[random.nextInt(TAGS.size)], mapOf("style" to "left:${random.nextInt(9)}px"), children)
    }

    fun screen(): VNode = VNode.element(screenNodeId, "div", mapOf("id" to "tw"), List(1 + random.nextInt(4)) { node(1) })

    fun edit(tree: VNode): VNode {
        val nodes = allNodes(tree).toList()
        val target = nodes[random.nextInt(nodes.size)]
        return when (random.nextInt(7)) {
            0 -> if (target.isText) {
                replace(tree, target, VNode.text(target.id, "edited${random.nextInt(9)}"))
            } else {
                val attrs = LinkedHashMap<String, String>()
                attrs["style"] = "left:${random.nextInt(99)}px"
                if (random.nextBoolean()) attrs["class"] = "c"
                replace(tree, target, VNode.element(target.id, target.tag!!, attrs, target.children))
            }
            1 -> if (target.isText) tree else {
                val children = target.children.toMutableList()
                children.add(random.nextInt(target.children.size + 1), node(2))
                replace(tree, target, withChildren(target, children))
            }
            2 -> if (target === tree) tree else remove(tree, target)
            3 -> if (target.isText || target.children.size < 2) tree else {
                val children = target.children.toMutableList()
                children.shuffle(random)
                replace(tree, target, withChildren(target, children))
            }
            4 -> if (target === tree) tree else {
                val without = remove(tree, target)
                val hosts = allNodes(without).filter { !it.isText }.toList()
                val host = hosts[random.nextInt(hosts.size)]
                val children = host.children.toMutableList()
                children.add(random.nextInt(host.children.size + 1), target)
                replace(without, host, withChildren(host, children))
            }
            5 -> if (target.isText || target === tree) tree else {
                replace(tree, target, VNode.element(target.id, if (target.tag == "div") "span" else "div", target.attributes, target.children))
            }
            else -> if (!target.isText) tree else replace(tree, target, VNode.element(target.id, "div", emptyMap(), emptyList()))
        }
    }

    companion object {
        val TAGS = listOf("div", "span", "img")

        fun withChildren(n: VNode, children: List<VNode>) = VNode.element(n.id, n.tag!!, n.attributes, children)

        fun replace(tree: VNode, target: VNode, replacement: VNode): VNode {
            if (tree === target) return replacement
            if (tree.isText) return tree
            var changed = false
            val children = tree.children.map {
                val r = replace(it, target, replacement)
                if (r !== it) changed = true
                r
            }
            return if (changed) withChildren(tree, children) else tree
        }

        fun remove(tree: VNode, target: VNode): VNode {
            if (tree.isText) return tree
            var changed = false
            val children = ArrayList<VNode>()
            for (c in tree.children) {
                if (c === target) {
                    changed = true
                    continue
                }
                val r = remove(c, target)
                if (r !== c) changed = true
                children.add(r)
            }
            return if (changed) withChildren(tree, children) else tree
        }
    }
}

/** The main thread of a test: tasks run as a [ManualScheduler]'s clock passes them. */
internal class ManualMainThread(private val scheduler: ManualScheduler) : MainThread {
    override fun isCurrent(): Boolean = true
    override fun post(delayMs: Long, task: () -> Unit): Cancellable = scheduler.schedule(delayMs, task)
}

/** Frames a test makes: whatever [screen] is when the recorder captures. */
internal class FakeSource : WireframeFrameSource {
    override var maskAllInputs = true
    var onDraw: (() -> Unit)? = null
    var screen: VNode? = null
    var width = 400f
    var height = 800f
    var captures = 0
    var resets = 0

    override fun observe(onDraw: () -> Unit) {
        this.onDraw = onDraw
    }

    override fun stopObserving() {
        onDraw = null
    }

    override fun capture(timestampMs: Long, href: String?): WireframeFrame? {
        captures++
        val s = screen ?: return null
        return WireframeFrame(s, width, height, timestampMs, href)
    }

    override fun reset() {
        resets++
    }

    /** The app drew a frame. */
    fun draw() = onDraw?.invoke()
}

/**
 * Screens of rows, built the way a capture builds them: a row that did not
 * change is the same object as in the previous screen.
 */
internal class RowScreens {
    private val rows = HashMap<Int, VNode>()

    fun screen(count: Int, label: (Int) -> String = { "row $it" }): VNode {
        val children = (0..count).map { i ->
            val text = label(i)
            val prev = rows[i]
            if (prev != null && prev.children.single().text == text) {
                prev
            } else {
                VNode.element(
                    100 + 2 * i, "div",
                    mapOf("class" to "t n", "style" to "left:0px;top:${i * 20}px;width:400px;height:20px;font-size:14px;"),
                    listOf(VNode.text(101 + 2 * i, text)),
                ).also { rows[i] = it }
            }
        }
        return VNode.element(screenNodeId, "div", mapOf("id" to "tw", "style" to "width:400px;height:800px"), children)
    }
}
