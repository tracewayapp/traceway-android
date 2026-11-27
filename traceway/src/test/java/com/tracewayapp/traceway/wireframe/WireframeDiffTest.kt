package com.tracewayapp.traceway.wireframe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

private fun box(id: Int, children: List<VNode> = emptyList(), style: String? = null): VNode =
    VNode.element(id, "div", if (style == null) emptyMap() else mapOf("style" to style), children)

private fun screenOf(children: List<VNode>): VNode = VNode.element(screenNodeId, "div", emptyMap(), children)

/** [mutation] played into [mirror] the way the dashboard gets it: through JSON, as an event. */
private fun replay(mirror: Mirror, mutation: Map<String, Any?>) =
    mirror.play(listOf(mapOf("type" to 3, "data" to mutation, "timestamp" to 1L)))

@Suppress("UNCHECKED_CAST")
private fun adds(m: Map<String, Any?>): List<Map<String, Any?>> = m["adds"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
private fun addedIds(m: Map<String, Any?>): List<Int> = adds(m).map { (it["node"] as Map<String, Any?>)["id"] as Int }

/**
 * The rrweb mutation between two frames ([diffScreens]), replayed through
 * the strict mirror of rrweb's replayer.
 */
class WireframeDiffTest {
    @Test
    fun `is null between the same screen`() {
        val a = TreeGen(Random(1)).screen()
        assertNull(diffScreens(a, a))
        // Equal content in different objects is no change either.
        val b = VNode.element(screenNodeId, "div", mapOf("id" to "tw"), a.children)
        assertNull(diffScreens(a, b))
        assertNull(diffScreens(a, VNode.element(screenNodeId, "div", mapOf("id" to "tw"), ArrayList(a.children))))
    }

    @Test
    fun `turns every random screen into its edit, as rrweb replays it`() {
        val random = Random(42)
        for (round in 0 until 400) {
            val gen = TreeGen(random).apply { nextId = 100 + round * 1000 }
            val before = gen.screen()
            var after = before
            repeat(random.nextInt(6) + 1) { after = gen.edit(after) }
            val mirror = Mirror()
            mirror.play(listOf(fullSnapshotOf(before)))
            assertEquals(dumpVNode(before), mirror.screen.dump())
            val mutation = diffScreens(before, after)
            if (mutation == null) {
                assertEquals(dumpVNode(before), dumpVNode(after))
                continue
            }
            replay(mirror, mutation)
            assertEquals("round $round", dumpVNode(after), mirror.screen.dump())
        }
    }

    @Test
    fun `changes one attribute without touching the rest`() {
        val text = VNode.text(101, "hi")
        val a = VNode.element(screenNodeId, "div", emptyMap(), listOf(VNode.element(100, "div", mapOf("style" to "left:1px"), listOf(text))))
        val b = VNode.element(screenNodeId, "div", emptyMap(), listOf(VNode.element(100, "div", mapOf("style" to "left:2px"), listOf(text))))
        val m = diffScreens(a, b)!!
        assertEquals(0, m["source"])
        assertEquals(emptyList<Any>(), m["adds"])
        assertEquals(emptyList<Any>(), m["removes"])
        assertEquals(emptyList<Any>(), m["texts"])
        assertEquals(listOf(mapOf("id" to 100, "attributes" to mapOf("style" to "left:2px"))), m["attributes"])
    }

    @Test
    fun `removed attributes are null and text changes are texts`() {
        val a = screenOf(listOf(VNode.element(100, "div", mapOf("style" to "a", "class" to "t"), listOf(VNode.text(101, "one")))))
        val b = screenOf(listOf(VNode.element(100, "div", mapOf("style" to "a"), listOf(VNode.text(101, "two")))))
        val m = diffScreens(a, b)!!
        assertEquals(listOf(mapOf("id" to 100, "attributes" to mapOf("class" to null))), m["attributes"])
        assertEquals(listOf(mapOf("id" to 101, "value" to "two")), m["texts"])
        assertEquals(emptyList<Any>(), m["adds"])
        assertEquals(emptyList<Any>(), m["removes"])
        // A removed attribute reaches the dashboard as null, and the replayer drops it.
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(a)))
        replay(mirror, m)
        assertEquals(dumpVNode(b), mirror.screen.dump())
    }

    @Test
    fun `a new subtree is added parent first and siblings last to first`() {
        val kept = box(100)
        val a = screenOf(listOf(kept))
        val added = box(200, listOf(box(201), box(202)))
        val b = screenOf(listOf(added, kept))
        val m = diffScreens(a, b)!!
        val adds = adds(m)
        assertEquals(listOf(200, 202, 201), addedIds(m))
        assertEquals(100, adds[0]["nextId"])
        assertEquals(screenNodeId, adds[0]["parentId"])
        assertNull(adds[1]["nextId"])
        assertEquals(200, adds[1]["parentId"])
        assertEquals(202, adds[2]["nextId"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("an add carries no children", emptyList<Any>(), (adds[0]["node"] as Map<String, Any?>)["childNodes"])
        assertEquals(emptyList<Any>(), m["removes"])
    }

    @Test
    fun `only the children outside the longest run move`() {
        val c = (100..104).map { box(it) }
        val a = screenOf(c)
        // 104 moves to the front; the other four keep their order.
        val b = screenOf(listOf(c[4], c[0], c[1], c[2], c[3]))
        val m = diffScreens(a, b)!!
        assertEquals(listOf(mapOf("parentId" to screenNodeId, "id" to 104)), m["removes"])
        assertEquals(listOf(104), addedIds(m))
        assertEquals(100, adds(m)[0]["nextId"])
        assertArrayEquals(intArrayOf(1, 2, 4), longestIncreasing(listOf(3, 1, 2, 0, 4)))
        assertArrayEquals(IntArray(0), longestIncreasing(emptyList()))
    }

    @Test
    fun `a node that moved among its siblings is the only one removed and added`() {
        val kids = (0 until 6).map { box(200 + it, style = "top:${it}px") }
        val a = screenOf(kids)
        val b = screenOf(kids.drop(1) + kids[0])
        val m = diffScreens(a, b)!!
        assertEquals(listOf(mapOf("parentId" to screenNodeId, "id" to 200)), m["removes"])
        val adds = adds(m)
        assertEquals(1, adds.size)
        assertEquals(listOf(200), addedIds(m))
        assertNull("the last child has no next sibling", adds[0]["nextId"])
        assertEquals(emptyList<Any>(), m["attributes"])
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(a)))
        replay(mirror, m)
        assertEquals(dumpVNode(b), mirror.screen.dump())
    }

    @Test
    fun `a node moved to another parent is added again with its subtree`() {
        val label = VNode.text(301, "moved")
        val moving = box(300, listOf(label, box(302)))
        val a = screenOf(listOf(box(100, listOf(moving)), box(200)))
        val b = screenOf(listOf(box(100), box(200, listOf(moving))))
        val m = diffScreens(a, b)!!
        assertEquals(listOf(mapOf("parentId" to 100, "id" to 300)), m["removes"])
        // The subtree is built node by node under its new parent.
        assertEquals(listOf(200, 300, 300), adds(m).map { it["parentId"] })
        assertEquals(listOf(300, 302, 301), addedIds(m))
        assertEquals("a text that moved with its parent is added, not changed", emptyList<Any>(), m["texts"])
        val mirror = Mirror()
        mirror.play(listOf(fullSnapshotOf(a)))
        replay(mirror, m)
        assertEquals(dumpVNode(b), mirror.screen.dump())
    }

    @Test
    fun `the longest increasing run is strictly increasing`() {
        val values = listOf(3, 1, 2, 5, 4, 6)
        val run = longestIncreasing(values).map { values[it] }
        assertEquals(4, run.size)
        assertEquals(run.sorted(), run)
        assertEquals(run.size, run.toSet().size)
        assertEquals("equal values do not extend a run", 1, longestIncreasing(listOf(5, 5, 5)).size)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), longestIncreasing(listOf(0, 1, 2, 3)))
        assertEquals(1, longestIncreasing(listOf(3, 2, 1)).size)
    }
}

/** The node model the diff and the snapshots are made of. */
class WireframeVNodeTest {
    @Test
    fun `nodes serialize as rrweb nodes`() {
        val node = VNode.element(20, "div", mapOf("style" to "left:1px"), listOf(VNode.text(21, "hi")))
        assertEquals(
            mapOf(
                "type" to 2, "tagName" to "div", "attributes" to mapOf("style" to "left:1px"), "id" to 20,
                "childNodes" to listOf(mapOf("type" to 3, "textContent" to "hi", "id" to 21)),
            ),
            node.serialize(),
        )
        assertEquals(emptyList<Any>(), node.serialize(withChildren = false)["childNodes"])
    }

    @Test
    fun `sameAs compares children by identity`() {
        val child = VNode.text(21, "hi")
        val a = VNode.element(20, "div", mapOf("a" to "1"), listOf(child))
        assertTrue(a.sameAs(VNode.element(20, "div", mapOf("a" to "1"), listOf(child))))
        assertFalse(a.sameAs(VNode.element(20, "div", mapOf("a" to "1"), listOf(VNode.text(21, "hi")))))
        assertFalse(a.sameAs(VNode.element(20, "div", mapOf("a" to "2"), listOf(child))))
        assertFalse(a.sameAs(VNode.element(20, "span", mapOf("a" to "1"), listOf(child))))
        assertFalse(a.sameAs(VNode.element(20, "div", mapOf("a" to "1", "b" to "2"), listOf(child))))
        assertFalse(a.sameAs(VNode.element(20, "div", mapOf("a" to "1"), listOf(child, child))))
    }

    @Test
    fun `the document holds the style and the screen`() {
        val screen = VNode.element(screenNodeId, "div", mapOf("id" to "tw"), emptyList())
        val doc = serializeDocument(screen)
        assertEquals(0, doc["type"])
        assertEquals(documentNodeId, doc["id"])
        val children = doc["childNodes"] as List<*>
        val doctype = children[0] as Map<*, *>
        assertEquals(1, doctype["type"])
        assertEquals("html", doctype["name"])
        val html = children[1] as Map<*, *>
        assertEquals("html", html["tagName"])
        val head = (html["childNodes"] as List<*>)[0] as Map<*, *>
        val style = (head["childNodes"] as List<*>)[1] as Map<*, *>
        assertEquals("style", style["tagName"])
        val css = (style["attributes"] as Map<*, *>)["_cssText"] as String
        for (rule in listOf("#tw{", ".t{", ".n{", ".e{", ".k{", ".im{", ".ic::before{", ".cp{", ".mk{", ".pv{")) {
            assertTrue(rule, css.contains(rule))
        }
        val body = (html["childNodes"] as List<*>)[1] as Map<*, *>
        assertEquals("body", body["tagName"])
        assertEquals(screenNodeId, ((body["childNodes"] as List<*>)[0] as Map<*, *>)["id"])
        // Every skeleton id is below the first captured one.
        val ids = ArrayList<Int>()
        fun collect(n: Map<*, *>) {
            (n["id"] as? Int)?.let { ids.add(it) }
            (n["childNodes"] as? List<*>)?.forEach { collect(it as Map<*, *>) }
        }
        collect(doc)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.toString(), ids.all { it < firstCapturedNodeId })
    }

    @Test
    fun `text nodes carry no tag, attributes or children`() {
        val text = VNode.text(30, "")
        assertTrue(text.isText)
        assertNull(text.tag)
        assertEquals(emptyMap<String, String>(), text.attributes)
        assertEquals(mapOf("type" to 3, "textContent" to "", "id" to 30), text.serialize())
        assertEquals(text.serialize(), text.serialize(withChildren = false))
        assertFalse("another id is another node", text.sameAs(VNode.text(31, "")))
        assertFalse(text.sameAs(VNode.element(30, "div", emptyMap(), emptyList())))
        assertFalse(VNode.element(30, "div", emptyMap(), emptyList()).isText)
    }
}
