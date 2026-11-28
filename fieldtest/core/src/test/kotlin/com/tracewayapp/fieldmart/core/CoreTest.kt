package com.tracewayapp.fieldmart.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PrngTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** Against an independent JavaScript implementation of 02-APP-SPEC.md §3.4 (prng-selfref.json, made with Node). */
    @Test
    fun matchesJavaScriptReference() {
        val ref = Json.parseToJsonElement(resource("prng-selfref.json")).jsonObject
        val p = Prng(Prng.BASE_SEED)
        for (v in ref["first10Seed20261128"]!!.jsonArray) assertEquals(v.jsonPrimitiveDouble(), p.next(), 0.0)
        for ((k, s) in ref["symbols"]!!.jsonObject) {
            val o = s.jsonObject
            val t = SymbolTicker(k.toInt(), o["startCents"]!!.jsonPrimitiveLong())
            for ((i, c) in o["ticks"]!!.jsonArray.withIndex()) assertEquals("symbol $k tick $i", c.jsonPrimitiveLong(), t.tick())
        }
    }

    /**
     * Against the campaign's reference ticks (fixtures/reference/ticks.json, 02-APP-SPEC.md §3.4): skipped until the
     * shared files are there. Accepts `{ "<symbol index>": { "startCents", "ticks": [...] } }`, under a top-level
     * `symbols` key or not, with ticks as cents or as `{ "n", "cents" }`.
     */
    @Test
    fun matchesCampaignReference() {
        val shared = System.getProperty("fm.shared") ?: ""
        val file = listOf(System.getProperty("fm.ticks"), "$shared/reference/ticks.json", "$shared/fixtures/reference/ticks.json")
            .filterNotNull().map(::File).firstOrNull { it.isFile }
        assumeTrue("no reference ticks.json yet", file != null)
        val root = Json.parseToJsonElement(file!!.readText()).jsonObject
        // Shared version 2: {"symbols": [{"k", "symbol", "startCents", "ticks": [...]}]}; also an object keyed by index.
        val entries: List<Pair<String, JsonElement>> = when (val sy = root["symbols"]) {
            is JsonArray -> sy.map { e -> ((e as JsonObject)["k"]!!.jsonPrimitiveLong().toString()) to e }
            is JsonObject -> sy.entries.map { it.key to it.value }
            else -> root.entries.map { it.key to it.value }
        }
        var checked = 0
        for ((k, s) in entries) {
            val index = k.toIntOrNull() ?: (s as? JsonObject)?.get("index")?.jsonPrimitiveLong()?.toInt() ?: continue
            val o = s as? JsonObject ?: continue
            val start = (o["startCents"] ?: o["start"])!!.jsonPrimitiveLong()
            val t = SymbolTicker(index, start)
            for ((i, c) in (o["ticks"] as JsonArray).withIndex()) {
                val expected = if (c is JsonObject) (c["cents"] ?: c["c"])!!.jsonPrimitiveLong() else c.jsonPrimitiveLong()
                assertEquals("symbol $index tick $i", expected, t.tick())
                checked++
            }
        }
        assertTrue("reference had no ticks", checked > 0)
    }

    @Test
    fun clockMovesSelectedEveryTickAndThirtyOthers() {
        val clock = MarketClock(LongArray(300) { 10000L + it }, selected = 0)
        clock.advanceTo(0)
        assertEquals(30, clock.changed.size)
        clock.advanceTo(9)
        assertEquals(10, clock.n)
        assertEquals(10, clock.symbols[0].ticks)
        // Symbols 30..59 are the second group: one tick each so far.
        assertEquals(1, clock.symbols[45].ticks)
        assertEquals("123.45", MarketClock.price(12345))
        assertEquals("0.05", MarketClock.price(5))
        assertEquals("+1.00%", MarketClock.changePercent(10100, 10000))
        assertEquals("-0.50%", MarketClock.changePercent(9950, 10000))
    }

    private fun JsonElement.jsonPrimitiveDouble() = (this as JsonPrimitive).double
    private fun JsonElement.jsonPrimitiveLong() = (this as JsonPrimitive).long
}

class JourneyParserTest {
    private val journey = Journey.parse(javaClass.classLoader!!.getResource("journey-sample.json")!!.readText())

    @Test
    fun readsTheSpecShape() {
        assertEquals(1, journey.version)
        assertEquals(412, journey.viewportWidth)
        val s = journey.scenario("S5")
        assertEquals("S5-checkout", s.id)
        assertEquals(90000, s.durationMs)
        assertEquals(11, s.steps.size)
        assertEquals(listOf("s5-form-empty", "s5-name-typed"), s.checkpoints.map { it.name })
        val typed = s.steps[4]
        assertEquals("type", typed.action)
        assertEquals(13 * 120L, typed.ownDurationMs())
        assertEquals(listOf("fm-checkout-name"), s.steps[5].masked)
        assertEquals(0.05 to 0.5, s.steps[8].from)
        assertEquals("Norway", s.steps[7].value)
        assertEquals(emptyList<String>(), validate(s))
    }

    @Test
    fun reportsWhatItCannotPlay() {
        val problems = validate(journey.scenario("S9-bad"))
        assertTrue(problems.joinToString("\n"), problems.any { it.contains("unknown action 'teleport'") })
        assertTrue(problems.any { it.contains("before the previous step") })
        assertTrue(problems.any { it.contains("is not fm-<screen>-<element>") })
        assertTrue(problems.any { it.contains("after durationMs") })
    }

    @Test
    fun resourcePatternsMatchViewsAndCompose() {
        val p = Regex(resourcePattern("fm-home-product-17"))
        assertTrue(p.matches("com.tracewayapp.fieldmart:id/fm_home_product_17"))
        assertTrue(p.matches("fm-home-product-17"))
        assertTrue(!p.matches("com.tracewayapp.fieldmart:id/fm_home_product_170"))
        assertTrue(!p.matches("fm-home-product-1"))
    }
}

/**
 * The dry run of the driver's journey parser on the campaign's `shared/journey.json` (skipped until the shared files
 * are there): every scenario parses, validates, and is printed as the timeline the driver will play
 * (core/build/journey-dry-run.txt).
 */
class JourneyDryRunTest {
    @Test
    fun campaignJourneyPlays() {
        val file = File(System.getProperty("fm.shared") ?: "", "journey.json")
        assumeTrue("no shared/journey.json yet", file.isFile)
        val journey = Journey.parse(file.readText())
        val report = StringBuilder()
        val problems = ArrayList<String>()
        for ((id, s) in journey.scenarios) {
            report.append(describe(s))
            for (p in validate(s)) {
                report.append("  ! ").append(p).append('\n')
                problems += "$id: $p"
            }
        }
        report.append("${journey.scenarios.size} scenarios, ${journey.scenarios.values.sumOf { it.checkpoints.size }} checkpoints\n")
        File("build").mkdirs()
        File("build/journey-dry-run.txt").writeText(report.toString())
        assertTrue(journey.scenarios.isNotEmpty())
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
