package com.tracewayapp.traceway.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {

    @Test
    fun `round trips nested values and keeps key order`() {
        val value = linkedMapOf(
            "z" to 1L,
            "a" to listOf(true, false, null, "x", 2.5, linkedMapOf("k" to emptyList<Any>())),
            "m" to emptyMap<String, Any>(),
            "s" to "",
        )
        val text = Json.stringify(value)
        assertEquals("""{"z":1,"a":[true,false,null,"x",2.5,{"k":[]}],"m":{},"s":""}""", text)
        assertEquals(value, Json.parse(text))
        assertEquals(listOf("z", "a", "m", "s"), (Json.parse(text) as Map<*, *>).keys.toList())
    }

    @Test
    fun `parses whitespace around and between tokens`() {
        assertEquals(mapOf("a" to listOf(1L, 2L)), Json.parse(" \n\t{ \"a\" : [ 1 , 2 ] }\r\n"))
        assertEquals(emptyList<Any>(), Json.parse("[ ]"))
        assertEquals(emptyMap<String, Any>(), Json.parse("{ }"))
        assertNull(Json.parse("null"))
        assertEquals(true, Json.parse(" true "))
    }

    @Test
    fun `escapes what JSON requires and reads every escape`() {
        val s = "q\" b\\ s/ n\n r\r t\t b\b f\u000C c\u0001 u\u001f ls\u2028 ps\u2029 é 😀"
        val text = Json.stringify(s)
        assertEquals(
            "\"q\\\" b\\\\ s/ n\\n r\\r t\\t b\\b f\\f c\\u0001 u\\u001f ls\\u2028 ps\\u2029 é 😀\"",
            text,
        )
        assertEquals(s, Json.parse(text))
        assertEquals("/é😀A", Json.parse("\"\\/\\u00e9\\ud83d\\uDE00\\u0041\""))
    }

    @Test
    fun `numbers parse as Long when integral and Double otherwise`() {
        assertEquals(0L, Json.parse("0"))
        assertEquals(-12L, Json.parse("-12"))
        assertEquals(9223372036854775807L, Json.parse("9223372036854775807"))
        assertEquals(9.223372036854775808E18, Json.parse("9223372036854775808"))
        assertEquals(1.5, Json.parse("1.5"))
        assertEquals(100000.0, Json.parse("1e5"))
        assertEquals(1.0E-7, Json.parse("1E-7"))
        assertEquals(250.0, Json.parse("2.5e+2"))
        assertEquals(-0.5, Json.parse("-0.5"))
        assertEquals(Double.POSITIVE_INFINITY, Json.parse("1e999"))
    }

    @Test
    fun `numbers are written as JSON numbers`() {
        assertEquals("[1,-2,3,600,0.5,1.0E-7,1.0E21,null,null,null,7,8]",
            Json.stringify(listOf(1, -2L, 3.toShort(), 600.0, 0.5, 1e-7, 1e21, Double.NaN, Double.POSITIVE_INFINITY, Float.NaN, 7.0f, 8.toByte())))
        // Every written number reads back.
        for (d in listOf(0.1, 1e-7, 1e21, -3.25e300, 123456.789)) {
            assertEquals(d, (Json.parse(Json.stringify(d)) as Number).toDouble(), 0.0)
        }
    }

    @Test
    fun `writes arrays, iterables and other values`() {
        assertEquals("[1,2]", Json.stringify(intArrayOf(1, 2)))
        assertEquals("[3,4]", Json.stringify(longArrayOf(3, 4)))
        assertEquals("[0.5]", Json.stringify(doubleArrayOf(0.5)))
        assertEquals("[\"a\",null]", Json.stringify(arrayOf("a", null)))
        assertEquals("[\"x\"]", Json.stringify(setOf("x")))
        assertEquals("{\"1\":\"c\"}", Json.stringify(mapOf(1 to 'c')))
    }

    @Test
    fun `rejects what is not one JSON value`() {
        for (bad in listOf(
            "",
            " ",
            "nul",
            "tru",
            "{",
            "[1,",
            "[1 2]",
            "{\"a\" 1}",
            "{\"a\":1,}",
            "[1,]",
            "{a:1}",
            "'a'",
            "\"open",
            "\"bad \\x escape\"",
            "\"\\u12\"",
            "\"\\u12G4\"",
            "\"\\u+041\"",
            "\"\\u\uFF10\uFF10\uFF14\uFF11\"",
            "\"raw\nnewline\"",
            "\"tab\there\"",
            "01",
            "-",
            "+1",
            ".5",
            "1.",
            "1.e5",
            "1e",
            "1e+",
            "--1",
            "0x10",
            "1.2.3",
            "NaN",
            "Infinity",
            "[1]x",
            "{} {}",
            "[".repeat(600) + "]".repeat(600),
        )) {
            try {
                Json.parse(bad)
                fail("accepted <$bad>")
            } catch (e: Json.ParseException) {
                assertTrue(e is IllegalArgumentException)
            }
        }
    }

    @Test
    fun `accepts deep but bounded nesting`() {
        val text = "[".repeat(500) + "]".repeat(500)
        var value = Json.parse(text)
        var depth = 0
        while (value is List<*> && value.isNotEmpty()) {
            value = value[0]
            depth++
        }
        assertEquals(499, depth)
    }

    @Test
    fun `helpers read numbers and containers`() {
        assertEquals(2.0, 2L.asFiniteDouble())
        assertNull(Double.NaN.asFiniteDouble())
        assertNull("2".asFiniteDouble())
        assertNull(true.asFiniteDouble())
        assertEquals(3L, 3.0.asLongExact())
        assertNull(3.5.asLongExact())
        assertNull(Double.POSITIVE_INFINITY.asLongExact())
        assertEquals(4L, 4.asLongExact())
        assertNull("x".asJsonObject())
        assertEquals(mapOf("a" to 1L), Json.parse("""{"a":1}""").asJsonObject())
        assertNull(mapOf("a" to 1).asJsonList())
        assertEquals(listOf(1L), Json.parse("[1]").asJsonList())
    }
}
