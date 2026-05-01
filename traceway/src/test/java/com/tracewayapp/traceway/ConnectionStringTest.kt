package com.tracewayapp.traceway

import com.tracewayapp.traceway.internal.parseConnectionString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionStringTest {

    @Test
    fun parsesValidConnectionString() {
        val parsed = parseConnectionString("abc-123@https://example.com/api/report")
        assertEquals("abc-123", parsed.token)
        assertEquals("https://example.com/api/report", parsed.apiUrl)
    }

    @Test
    fun parsesUrlContainingAtSign() {
        // First @ is the delimiter; subsequent @s belong to the URL.
        val parsed = parseConnectionString("token@https://user@example.com/api")
        assertEquals("token", parsed.token)
        assertEquals("https://user@example.com/api", parsed.apiUrl)
    }

    @Test
    fun rejectsMissingAtSign() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parseConnectionString("no-at-sign-here")
        }
        assert(ex.message!!.contains("must be in format")) { ex.message!! }
    }

    @Test
    fun rejectsEmptyToken() {
        assertThrows(IllegalArgumentException::class.java) {
            parseConnectionString("@https://example.com/api")
        }
    }

    @Test
    fun rejectsEmptyApiUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            parseConnectionString("token@")
        }
    }
}
