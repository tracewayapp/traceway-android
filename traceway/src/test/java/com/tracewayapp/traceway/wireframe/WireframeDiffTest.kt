package com.tracewayapp.traceway.wireframe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class WireframeDiffTest {
    @Test
    fun `is null between the same screen`() {
        val a = TreeGen(Random(1)).screen()
        assertNull(diffScreens(a, a))
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
            mirror.play(listOf(mapOf("type" to 3, "data" to mutation, "timestamp" to 1L)))
            assertEquals("round $round", dumpVNode(after), mirror.screen.dump())
        }
    }

    @Test
    fun `changes one attribute without touching the rest`() {
        val text = VNode.text(101, "hi")
        val a = VNode.element(screenNodeId, "div", emptyMap(), listOf(VNode.element(100, "div", mapOf("style" to "left:1px"), listOf(text))))
        val b = VNode.element(screenNodeId, "div", emptyMap(), listOf(VNode.element(100, "div", mapOf("style" to "left:2px"), listOf(text))))
        val m = diffScreens(a, b)!!
        assertEquals(emptyList<Any>(), m["adds"])
        assertEquals(emptyList<Any>(), m["removes"])
        assertEquals(listOf(mapOf("id" to 100, "attributes" to mapOf("style" to "left:2px"))), m["attributes"])
    }

    @Test
    fun `a node that moved among its siblings is the only one removed and added`() {
        val kids = (0 until 6).map { VNode.element(200 + it, "div", mapOf("style" to "top:${it}px"), emptyList()) }
        val a = VNode.element(screenNodeId, "div", emptyMap(), kids)
        val moved = kids.toMutableList().apply { add(removeAt(0)) }
        val b = VNode.element(screenNodeId, "div", emptyMap(), moved)
        val m = diffScreens(a, b)!!
        assertEquals(listOf(mapOf("parentId" to screenNodeId, "id" to 200)), m["removes"])
        @Suppress("UNCHECKED_CAST")
        val adds = m["adds"] as List<Map<String, Any?>>
        assertEquals(1, adds.size)
        assertEquals(null, adds[0]["nextId"])
    }

    @Test
    fun `longest increasing subsequence`() {
        assertArrayEquals(IntArray(0), longestIncreasing(emptyList()))
        val values = listOf(3, 1, 2, 5, 4, 6)
        val run = longestIncreasing(values).map { values[it] }
        assertEquals(4, run.size)
        assertTrue(run.zipWithNext().all { (x, y) -> x < y })
    }
}
