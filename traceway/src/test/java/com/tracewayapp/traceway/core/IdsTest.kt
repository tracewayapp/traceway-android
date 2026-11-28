package com.tracewayapp.traceway.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {

    @Test
    fun `uuidv7 carries the timestamp, version and variant`() {
        val id = Ids.uuidv7(0x0195f2a00000L)
        assertTrue(id, id.startsWith("0195f2a0-0000-7"))
        assertTrue(id, id[19] in "89ab")
        assertEquals(36, id.length)
        val bytes = Ids.uuidToBytes(id)!!
        assertEquals(16, bytes.size)
        assertEquals(id, Ids.bytesToUuid(bytes))
        assertTrue(Ids.uuidv7(-5).startsWith("00000000-0000-7"))
        assertNotEquals(Ids.uuidv7(1), Ids.uuidv7(1))
    }

    @Test
    fun `uuidv4 carries version and variant`() {
        val id = Ids.uuidv4()
        assertEquals('4', id[14])
        assertTrue(id, id[19] in "89ab")
        assertNotEquals(id, Ids.uuidv4())
    }

    @Test
    fun `hex round trips and rejects what is not hex`() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        assertEquals("00017f80ff", Ids.bytesToHex(bytes))
        assertArrayEquals(bytes, Ids.hexToBytes("00017F80ff"))
        assertNull(Ids.hexToBytes(""))
        assertNull(Ids.hexToBytes("abc"))
        assertNull(Ids.hexToBytes("zz"))
        // Full-width and Arabic-Indic digits are digits to Character.digit, not hex.
        assertNull(Ids.hexToBytes("０１"))
        assertNull(Ids.hexToBytes("١٢"))
        assertNull(Ids.uuidToBytes("0195f2a0-0000-7"))
    }

    /** Expected values computed with the browser SDK's `unitHash` (js-client packages/core/src/ids.ts) under node. */
    @Test
    fun `unitHash matches the browser SDK (FNV-1a over UTF-16 code units)`() {
        val expected = linkedMapOf(
            "" to 2166136261L,
            "a" to 3826002220L,
            "foobar" to 3214735720L,
            "session-1" to 1936347597L,
            "0190d1c2-3e4f-7a5b-8c6d-9e0f1a2b3c4d" to 2764980149L,
            "é€😀" to 207495949L,
        )
        for ((value, hash) in expected) {
            assertEquals(value, hash / 4294967296.0, Ids.unitHash(value), 0.0)
        }
        assertEquals(0.50434289989061654, Ids.unitHash(""), 0.0)
        assertEquals(0.45084105734713376, Ids.unitHash("session-1"), 0.0)
        val h = Ids.unitHash("session")
        assertTrue(h >= 0 && h < 1)
    }
}
