package com.tracewayapp.traceway.core

import com.tracewayapp.traceway.internal.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private fun lookupOf(values: Map<String, Any?>): FieldLookup = { values[it] }

    private val record = lookupOf(
        mapOf(
            "event.name" to "checkout_failed",
            "attributes.status" to 503L,
            "attributes.ratio" to 0.5,
            "attributes.flag" to true,
            "attributes.empty" to "",
            "resource.service.version" to "2.3.1",
            "body" to "Payment FAILED hard",
        ),
    )

    private fun m(key: String, op: String, value: Any? = null, values: List<Any>? = null) =
        matches(Condition(key, op, value, values), record)

    @Test
    fun operators() {
        assertTrue(m("event.name", "eq", "checkout_failed"))
        assertTrue(m("event.name", "neq", "x"))
        assertTrue(m("attributes.status", "eq", "503"))
        assertTrue(m("attributes.status", "eq", 503.0))
        assertTrue(m("attributes.status", "gte", 500L))
        assertFalse(m("attributes.status", "lt", 500L))
        assertTrue(m("attributes.status", "in", values = listOf(500L, 503L)))
        assertFalse(m("attributes.status", "not_in", values = listOf(500L, 503L)))
        assertTrue(m("body", "contains", "FAILED"))
        assertFalse(m("body", "contains", "failed"))
        assertTrue(m("body", "icontains", "failed"))
        assertTrue(m("body", "starts_with", "Payment"))
        assertTrue(m("body", "glob", "pay*fail?d*"))
        assertFalse(m("missing", "exists"))
        assertTrue(m("missing", "not_exists"))
        assertTrue(m("resource.service.version", "version", ">=2.1 <3"))
        assertFalse(m("resource.service.version", "version", "<2.3"))
    }

    @Test
    fun `operators across types and absent values`() {
        // An empty string is absent.
        assertFalse(m("attributes.empty", "exists"))
        assertTrue(m("attributes.empty", "not_exists"))
        assertFalse(m("attributes.empty", "contains", ""))
        assertTrue(m("attributes.flag", "eq", true))
        assertTrue(m("attributes.flag", "eq", "true"))
        assertTrue(m("attributes.ratio", "eq", " 0.5 "))
        assertTrue(m("attributes.ratio", "lt", "1e0"))
        assertTrue(m("attributes.status", "contains", 50L))
        // Numbers are compared as numbers only when both sides are decimal numbers.
        assertFalse(m("attributes.status", "gt", "0x10"))
        assertFalse(m("attributes.status", "gt", "1d"))
        assertFalse(m("attributes.status", "gt", "Infinity"))
        assertFalse(m("event.name", "gt", 1L))
        assertFalse(m("missing", "eq", "x"))
        assertTrue(m("missing", "neq", "x"))
        assertTrue(m("missing", "eq", null))
        assertFalse(m("missing", "in"))
        assertTrue(m("missing", "not_in"))
        assertFalse(m("body", "contains"))
        assertFalse(m("body", "regex", ".*"))
        assertFalse(m("missing", "version", ">=1"))
    }

    @Test
    fun `text conditions look at the first 4096 characters only`() {
        val long = "a".repeat(5000) + "needle"
        val lookup = lookupOf(mapOf("body" to long))
        assertFalse(matches(Condition("body", "contains", "needle"), lookup))
        assertTrue(matches(Condition("body", "glob", "a*"), lookup))
    }

    @Test
    fun `glob is case-insensitive with star and question mark`() {
        assertTrue(globMatch("Hello", "h*O"))
        assertTrue(globMatch("", "*"))
        assertTrue(globMatch("abc", "a?c"))
        assertFalse(globMatch("ac", "a?c"))
        assertTrue(globMatch("aXbXc", "*b*c"))
        assertFalse(globMatch("abc", "ab"))
        assertTrue(globMatch("a*b", "a*b"))
        assertTrue(globMatch("mississippi", "m*iss*ppi"))
        assertFalse(globMatch("mississippi", "m*iss*ppx"))
        // Linear-ish even on adversarial input.
        assertFalse(globMatch("a".repeat(4096), "*a*a*a*a*a*a*a*b"))
    }

    @Test
    fun versions() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertEquals(-1, compareVersions("1.2", "1.10"))
        assertEquals(1, compareVersions("v2", "1.99"))
        assertEquals(0, compareVersions(" 2.3.1-beta ", "2.3.1"))
        assertNull(compareVersions("beta", "1"))
        assertTrue(satisfiesVersion("2.3.1", ">=2.1,<3"))
        assertTrue(satisfiesVersion("2.3.1", "  ==2.3.1  "))
        assertTrue(satisfiesVersion("2.3.1", "=2.3.1"))
        assertTrue(satisfiesVersion("2.3.1", "2.3.1"))
        assertTrue(satisfiesVersion("2.3.1", "!=2.3.0"))
        assertTrue(satisfiesVersion("2.3.1", ">2.3\u00A0<2.4"))
        assertFalse(satisfiesVersion("2.3.1", ">2.3.1"))
        assertFalse(satisfiesVersion("2.3.1", ""))
        assertFalse(satisfiesVersion("2.3.1", ">="))
        assertFalse(satisfiesVersion("2.3.1", ">=x"))
    }

    @Test
    fun `a version part too long for an int is not a version, as on the server`() {
        assertFalse(satisfiesVersion("1.99999999999999999999", ">=1"))
        assertFalse(satisfiesVersion("2.0", ">=1.99999999999999999999"))
        assertNull(compareVersions("12345678901234567890", "1"))
        assertEquals(1, compareVersions("9223372036854775807", "1"))
    }

    @Test
    fun `all, any and not combine`() {
        val lookup = lookupOf(mapOf("a" to 1L, "b" to 2L))
        val a = Condition("a", "eq", 1L)
        val b = Condition("b", "eq", 9L)
        assertFalse(matches(AllMatcher(listOf(a, b)), lookup))
        assertTrue(matches(AnyMatcher(listOf(a, b)), lookup))
        assertTrue(matches(NotMatcher(b), lookup))
        assertTrue(matches(AllMatcher(emptyList()), lookup))
        assertFalse(matches(AnyMatcher(emptyList()), lookup))
    }

    private fun navigation(url: Any?): FieldLookup = {
        when (it) {
            "event.name" -> "browser.navigation"
            "attributes.url.full" -> url
            else -> null
        }
    }

    @Test
    fun `matches exactly one route segment and preserves case`() {
        val matcher = EventMatcher("navigation", "/users/{id}")
        for ((url, expected) in listOf(
            "/users/42" to true,
            "/users/42/edit" to false,
            "/Users/42" to false,
            "/users/" to false,
            "users/42" to true,
            "/users/42?tab=1" to true,
            "https://app.test/users/42?secret=hidden#x" to true,
            "myapp://host/users/42" to true,
            "/users/a%2Fb" to true,
        )) {
            assertEquals(url, expected, matches(matcher, navigation(url)))
        }
        assertTrue(matchesRoute("/café", "/caf%C3%A9"))
        assertTrue(matchesRoute("/a b", "/a%20b"))
        assertTrue(matchesRoute("/a+b", "/a+b"))
        assertFalse(matchesRoute("/a b", "/a+b"))
        assertFalse(matchesRoute("/x", "/%E9"))
        assertFalse(matchesRoute("/{id}/x", "//x"))
        assertTrue(matchesRoute("/{id}", "/%zz"))
        assertFalse(matchesRoute("/{1d}", "/42"))
        assertFalse(matches(matcher, navigation(null)))
        assertFalse(matches(matcher, navigation("")))
        assertFalse(matches(EventMatcher("navigation", "/users/42"), lookupOf(mapOf("attributes.url.full" to "/users/42"))))
    }

    /** Expected paths computed with Dart's `Uri.parse('https://traceway.invalid/').resolve(url).path`, as the Flutter SDK reads them. */
    @Test
    fun `pathOf reads the path as the Flutter SDK does`() {
        for ((url, expected) in listOf(
            "https://app.test/users/42?secret=hidden" to "/users/42",
            "/users/42?tab=1" to "/users/42",
            "users/42" to "/users/42",
            "https://app.test" to "",
            "https://app.test/" to "/",
            "?tab=1" to "/",
            "#x" to "/",
            "../x" to "/x",
            "/a/./b/../c" to "/a/c",
            "a/../../b" to "/b",
            "/café" to "/caf%C3%A9",
            "/caf%c3%a9" to "/caf%C3%A9",
            "/%7Euser" to "/~user",
            "/a%41b" to "/aAb",
            "/a%2fb" to "/a%2Fb",
            "/a b" to "/a%20b",
            "/a%zzb" to "/a%25zzb",
            "/a%2" to "/a%252",
            "/%" to "/%25",
            "mailto:x" to "x",
            "myapp://users/42" to "/42",
            "MainActivity" to "/MainActivity",
            "com.example.MainActivity" to "/com.example.MainActivity",
            "//host/p" to "/p",
            "/a\\b" to "/a/b",
            "/a|b{c}" to "/a%7Cb%7Bc%7D",
            "/x:y@z!\$&'()*+,;=" to "/x:y@z!\$&'()*+,;=",
            " /users" to "/%20/users",
            "a:b/c" to "b/c",
            "/\uD83D\uDE00" to "/%F0%9F%98%80",
            "/a//b" to "/a//b",
            "https://h/a/../../b" to "/b",
            "myapp:a/../b" to "/b",
            "/a/.." to "/",
            "/a/." to "/a/",
            "." to "/",
            ".." to "/",
            "file:///x" to "/x",
            "FILE:x" to "/x",
            "x:" to "",
            "C:\\path" to "/path",
            "a/b:c" to "/a/b:c",
            "/users/42;x" to "/users/42;x",
            "/%%41" to "/%25A",
        )) {
            assertEquals(url, expected, pathOf(url))
        }
        assertNull(pathOf(null))
        assertNull(pathOf(""))
        assertNull(pathOf("1abc:x"))
        assertNull(pathOf(":x"))
        assertEquals("/42", pathOf(42L))
    }

    @Test
    fun `logs match exactly or by contains, only console and message records`() {
        val console: FieldLookup = { if (it == "event.name") "browser.console" else "some loaded message" }
        assertFalse(matches(EventMatcher("log", "loaded"), console))
        assertTrue(matches(EventMatcher("log", "loaded", mode = "contains"), console))
        assertTrue(matches(EventMatcher("log", "hello"), lookupOf(mapOf("signal" to "messages", "body" to "hello"))))
        assertTrue(matches(EventMatcher("log", "hello"), lookupOf(mapOf("event.name" to "traceway.message", "body" to "hello"))))
        assertTrue(matches(EventMatcher("log", "hello"), lookupOf(mapOf("signal" to "console", "body" to "hello"))))
        assertFalse(matches(EventMatcher("log", "hello"), lookupOf(mapOf("event.name" to "custom", "body" to "hello"))))
        assertTrue(matches(EventMatcher("log", "42"), lookupOf(mapOf("signal" to "console", "body" to 42L))))
        val long = lookupOf(mapOf("signal" to "console", "body" to "a".repeat(4096) + "tail"))
        assertFalse(matches(EventMatcher("log", "tail", mode = "contains"), long))
        assertTrue(matches(EventMatcher("log", "a".repeat(4096)), long))
    }

    @Test
    fun `taps and hovers ask the client's selector test`() {
        val asked = ArrayList<String>()
        fun pointer(action: String, result: Boolean): FieldLookup = {
            when (it) {
                KEY_POINTER_ACTION -> action
                KEY_TARGET_MATCHES -> { selector: String -> asked.add(selector); result }
                else -> null
            }
        }
        assertTrue(matches(EventMatcher("click", "#buy"), pointer("click", true)))
        assertEquals(listOf("#buy"), asked)
        assertFalse(matches(EventMatcher("click", "#buy"), pointer("click", false)))
        assertFalse(matches(EventMatcher("hover", "#buy"), pointer("click", true)))
        assertTrue(matches(EventMatcher("hover", "#menu"), pointer("hover", true)))
        assertFalse(matches(EventMatcher("click", "#buy"), lookupOf(mapOf(KEY_POINTER_ACTION to "click"))))
        assertFalse(
            matches(
                EventMatcher("click", "#buy"),
                lookupOf(mapOf(KEY_POINTER_ACTION to "click", KEY_TARGET_MATCHES to "#buy")),
            ),
        )
        val throwing: FieldLookup = {
            when (it) {
                KEY_POINTER_ACTION -> "click"
                KEY_TARGET_MATCHES -> { _: String -> throw IllegalStateException("view gone") }
                else -> null
            }
        }
        assertFalse(matches(EventMatcher("click", "#buy"), throwing))
        assertFalse(matches(EventMatcher("unknown", "#buy"), pointer("click", true)))
    }

    @Test
    fun `validates bounded event matchers`() {
        fun parse(json: String) = parseMatcher(Json.parse(json))
        assertTrue(parse("""{"event":{"type":"hover","value":"#menu"}}""") is EventMatcher)
        val log = parse("""{"event":{"type":"log","value":"x","mode":"contains"}}""") as EventMatcher
        assertEquals("contains", log.mode)
        for (bad in listOf(
            """{"type":"click","value":":has(*)"}""",
            """{"type":"click","value":"a, b"}""",
            """{"type":"hover","value":"a\\b"}""",
            """{"type":"unknown","value":"x"}""",
            """{"type":"log","value":" "}""",
            """{"type":"log","value":"x","mode":"regex"}""",
            """{"type":"log","value":"${"x".repeat(257)}"}""",
            """{"type":"log","value":5}""",
        )) {
            assertNull(bad, parse("""{"event":$bad}"""))
        }
        assertTrue(parse("""{"event":{"type":"log","value":"a: b, c"}}""") is EventMatcher)
        assertTrue(parse("""{"event":{"type":"log","value":"${"x".repeat(256)}"}}""") is EventMatcher)
    }
}
