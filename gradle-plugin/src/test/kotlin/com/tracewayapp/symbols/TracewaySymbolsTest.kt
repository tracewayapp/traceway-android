package com.tracewayapp.symbols

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class TracewaySymbolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deriveProguardUuidIsStableAndValid() {
        val a = TracewaySymbols.deriveProguardUuid(":app:release:1.0.0")
        val b = TracewaySymbols.deriveProguardUuid(":app:release:1.0.0")
        val c = TracewaySymbols.deriveProguardUuid(":app:release:1.0.1")
        assertEquals("same seed -> same uuid", a, b)
        assertTrue("different seed -> different uuid", a != c)
        assertTrue("looks like a uuid", Regex("^[0-9a-f-]{36}$").matches(a))
    }

    @Test
    fun uploadEndpointNormalizesUrls() {
        assertEquals("https://x.com/api/symbols/upload", TracewaySymbols.uploadEndpoint("https://x.com"))
        assertEquals("https://x.com/api/symbols/upload", TracewaySymbols.uploadEndpoint("https://x.com/"))
        assertEquals("https://x.com/api/symbols/upload", TracewaySymbols.uploadEndpoint("https://x.com/api/report"))
        assertEquals("https://x.com/api/symbols/upload", TracewaySymbols.uploadEndpoint("https://x.com/api/symbols/upload"))
    }

    @Test
    fun multipartBodyHasFieldsAndFiles() {
        val mapping = tmp.newFile("mapping.txt").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val parts = listOf(
            TracewaySymbols.textPart("proguard_uuid", "6a8cb813-45f6-3652-ad33-778fd1eab196"),
            TracewaySymbols.filePart("files", mapping, "text/plain"),
        )
        val body = TracewaySymbols.buildMultipartBody("BOUND", parts).toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"proguard_uuid\""))
        assertTrue(body.contains("6a8cb813-45f6-3652-ad33-778fd1eab196"))
        assertTrue(body.contains("name=\"files\"; filename=\"mapping.txt\""))
        assertTrue(body.contains("Content-Type: text/plain"))
        assertTrue(body.trimEnd().endsWith("--BOUND--"))
    }

    @Test
    fun multipartHeaderValuesAreSanitized() {
        val parts = listOf(TracewaySymbols.textPart("a\r\nInjected: x", "v"))
        val body = TracewaySymbols.buildMultipartBody("BOUND", parts).toString(Charsets.ISO_8859_1)
        assertTrue("no injected header line", !body.contains("\r\nInjected: x"))
        assertTrue(body.contains("name=\"aInjected: x\""))
    }

    @Test
    fun uploadSendsBearerAndMultipartAndReturnsStatus() {
        val mapping = tmp.newFile("mapping.txt").apply { writeText("com.example.A -> a.a:") }
        var capMethod = ""
        var capAuth = ""
        var capContentType = ""
        var capBody = ByteArray(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/symbols/upload") { ex ->
            capMethod = ex.requestMethod
            capAuth = ex.requestHeaders.getFirst("Authorization") ?: ""
            capContentType = ex.requestHeaders.getFirst("Content-Type") ?: ""
            capBody = ex.requestBody.readBytes()
            val resp = "{\"uploaded\":1}".toByteArray()
            ex.sendResponseHeaders(201, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val port = server.address.port
            val result = TracewaySymbols.upload(
                "http://127.0.0.1:$port/api/symbols/upload",
                "tok123",
                listOf(
                    TracewaySymbols.textPart("proguard_uuid", "6a8cb813-45f6-3652-ad33-778fd1eab196"),
                    TracewaySymbols.filePart("files", mapping, "text/plain"),
                ),
            )

            assertEquals(201, result.status)
            assertTrue(result.body.contains("uploaded"))
            assertEquals("POST", capMethod)
            assertEquals("Bearer tok123", capAuth)
            assertTrue(capContentType.startsWith("multipart/form-data; boundary="))

            val bodyStr = capBody.toString(Charsets.ISO_8859_1)
            assertTrue(bodyStr.contains("name=\"proguard_uuid\""))
            assertTrue(bodyStr.contains("6a8cb813-45f6-3652-ad33-778fd1eab196"))
            assertTrue(bodyStr.contains("name=\"files\"; filename=\"mapping.txt\""))
            assertTrue(bodyStr.contains("com.example.A -> a.a:"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun uploadSurfacesErrorStatusAndBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/symbols/upload") { ex ->
            ex.requestBody.readBytes()
            val resp = "{\"error\":\"bad token\"}".toByteArray()
            ex.sendResponseHeaders(401, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val port = server.address.port
            val result = TracewaySymbols.upload(
                "http://127.0.0.1:$port/api/symbols/upload",
                "tok123",
                listOf(TracewaySymbols.textPart("proguard_uuid", "6a8cb813-45f6-3652-ad33-778fd1eab196")),
            )
            assertEquals(401, result.status)
            assertTrue(result.body.contains("bad token"))
        } finally {
            server.stop(0)
        }
    }
}
