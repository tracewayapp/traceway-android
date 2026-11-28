package com.tracewayapp.traceway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ignoreErrors`: what each pattern matches and the line it is matched
 * against. The client applies them, and `beforeCapture`, in
 * [TracewayClientTest.ignoredErrorsAndBeforeCaptureDropIssues].
 */
class ErrorFilterTest {
    private fun line(type: String?, message: String) =
        TracewayCapturedError(null, type, message, type == null, emptyMap(), null).line

    private fun ignoredByDefault(type: String?, message: String) =
        ignoreErrorsMatch(TracewayOptions.DEFAULT_IGNORE_ERRORS, line(type, message))

    @Test
    fun theLineIsTypeColonMessageFirstLineOnly() {
        assertEquals("com.shop.CheckoutError: card declined", line("com.shop.CheckoutError", "card declined"))
        assertEquals("E: boom", line("E", "boom\n\tat com.shop.Cart.pay(Cart.kt:12)"))
        assertEquals("an exception without a message", "java.lang.IllegalStateException", line("java.lang.IllegalStateException", ""))
        assertEquals("plain", line(null, "plain\nsecond"))
        assertEquals("", line(null, ""))
    }

    @Test
    fun aStringMatchesAsASubstringAndARegexSearches() {
        assertTrue(ignoreErrorsMatch(listOf("declined"), "com.shop.CheckoutError: card declined"))
        assertFalse("strings are case-sensitive", ignoreErrorsMatch(listOf("declined"), "com.shop.CheckoutError: card Declined"))
        assertTrue(ignoreErrorsMatch(listOf("Error: c"), "com.shop.CheckoutError: card"))
        val anchored = Regex("^Noise\\b")
        assertTrue(ignoreErrorsMatch(listOf(anchored), "Noise level high"))
        assertFalse(ignoreErrorsMatch(listOf(anchored), "More Noise"))
        assertTrue(ignoreErrorsMatch(listOf(Regex("noise", RegexOption.IGNORE_CASE)), "LOUD NOISE"))
        assertTrue(ignoreErrorsMatch(listOf(java.util.regex.Pattern.compile("code \\d+")), "failed with code 42"))
        // Non-ASCII text keeps its ranges right.
        assertTrue(ignoreErrorsMatch(listOf(Regex("ü$")), "Grüße: ü"))
        // Any pattern of the list is enough.
        assertTrue(ignoreErrorsMatch(listOf("nope", Regex("card")), "card declined"))
    }

    @Test
    fun anEmptyStringOrAPatternOfAnotherKindMatchesNothing() {
        assertFalse("an empty string occurs in every line, so it would ignore everything", ignoreErrorsMatch(listOf(""), "anything"))
        assertFalse(ignoreErrorsMatch(listOf(""), ""))
        assertFalse(ignoreErrorsMatch(listOf(42, null, 'c', listOf("card")), "card 42 c"))
        assertFalse("an empty list reports everything", ignoreErrorsMatch(emptyList(), "java.net.UnknownHostException"))
    }

    @Test
    fun theDefaultsIgnoreNetworkNoise() {
        for ((type, message) in listOf(
            "java.net.UnknownHostException" to "Unable to resolve host \"api.example.com\": No address associated with hostname",
            "java.net.SocketTimeoutException" to "failed to connect to api.example.com/10.0.0.1 (port 443) after 10000ms",
            "java.net.ConnectException" to "Failed to connect to /10.0.2.2:8080",
            "javax.net.ssl.SSLHandshakeException" to "Chain validation failed",
            "java.net.NoRouteToHostException" to "No route to host",
            "java.net.SocketException" to "Connection reset by peer",
            "java.io.InterruptedIOException" to "timeout",
            "java.io.IOException" to "Read Timeout",
        )) {
            assertTrue("$type: $message", ignoredByDefault(type, message))
        }
        assertTrue(ignoredByDefault(null, "Response status code of 404"))
        assertTrue(ignoredByDefault(null, "Request failed with status code 401"))
        assertTrue(ignoredByDefault(null, "Upload failed: 413"))
        assertTrue("a wrapped network error", ignoredByDefault("java.lang.RuntimeException", "java.net.UnknownHostException: api.example.com"))
    }

    @Test
    fun theDefaultsReportRealErrors() {
        assertFalse(ignoredByDefault(null, "Upload failed: 500"))
        assertFalse(ignoredByDefault(null, "status code 500"))
        assertFalse(ignoredByDefault("com.shop.CheckoutError", "card declined"))
        assertFalse("a configuration bug, not the network", ignoredByDefault("java.net.UnknownServiceException", "CLEARTEXT communication to api.example.com not permitted by network security policy"))
        assertFalse(ignoredByDefault("javax.net.ssl.SSLPeerUnverifiedException", "Hostname api.example.com not verified"))
        // Only the first line counts: a stack frame named like a pattern does not silence the error.
        assertFalse(ignoredByDefault("com.shop.CheckoutError", "card declined\n\tat com.shop.Retry.onTimeout(Retry.kt:3)"))
        assertFalse(ignoredByDefault("java.lang.IllegalStateException", ""))
    }
}
