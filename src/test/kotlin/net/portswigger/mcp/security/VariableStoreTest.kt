package net.portswigger.mcp.security

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class VariableStoreTest {
    private val canary = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.signature-Canary+/="

    @Test
    fun `holder cannot serialize its value and only renders a handle`() {
        val store = VariableStore()
        assertEquals("{{canary}}", store.put("canary", canary, "admin", "api.target"))
        val field = VariableStore::class.java.getDeclaredField("values").apply { isAccessible = true }
        val holder = (field.get(store) as Map<*, *>).values.single()
        assertFalse(holder is Serializable)
        assertEquals("{{canary}}", holder.toString())
        assertFalse(holder.toString().contains(canary))
        assertEquals(listOf(VariableStore.Name("canary", "admin", "api.target")), store.listNames())
        store.clear()
        assertTrue(store.listNames().isEmpty())
    }

    @Test
    fun `redacts raw base64 and URL encoded secret forms`() {
        val store = VariableStore()
        store.put("canary", canary, "admin", "api.target")
        val base64 = Base64.getEncoder().encodeToString(canary.toByteArray(StandardCharsets.UTF_8))
        val encoded = URLEncoder.encode(canary, StandardCharsets.UTF_8)
        val output = store.redact("$canary $base64 $encoded")
        assertEquals("{{canary}} {{canary}} {{canary}}", output)
        assertFalse(output.contains(canary))
    }

    @Test
    fun `literal handle text cannot be stored as a credential or token`() {
        val store = VariableStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.put("username", "{{username}}", "guest_role", "auth.target")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put("session", "Bearer {{session}}", "guest_role", "api.target")
        }
        assertTrue(store.listNames().isEmpty())
    }

    @Test
    fun `cross identity unknown variable and wrong host fail before an HTTP request is built`() {
        val store = VariableStore()
        store.put("session", canary, "admin", "api.target")
        val template = "curl -s https://api.target/private -H 'Authorization: Bearer {{session}}'"
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            val service = mockk<HttpService>()
            every { HttpService.httpService("api.target", 443, true) } returns service
            assertThrows(IllegalArgumentException::class.java) { store.expand(template, "user", "api.target") }
            assertThrows(IllegalArgumentException::class.java) { store.expand(template, "admin", "other.target") }
            assertThrows(IllegalArgumentException::class.java) {
                store.expand("curl https://api.target/ -H 'Authorization: Bearer {{missing}}'", "admin", "api.target")
            }
            io.mockk.verify(exactly = 0) { HttpRequest.httpRequest(any<HttpService>(), any<String>()) }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `curl placeholders expand in path and body and update content length`() {
        val store = VariableStore().apply {
            put("user_id", "17", "user", "api.target")
            put("session", canary, "user", "api.target")
        }
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            val service = mockk<HttpService>()
            val content = slot<String>()
            every { HttpService.httpService("api.target", 443, true) } returns service
            every { HttpRequest.httpRequest(service, capture(content)) } returns mockk()
            store.expand(
                "curl https://api.target/users/{{user_id}} -H 'Authorization: Bearer {{session}}' --json '{\"id\":\"{{user_id}}\"}'",
                "user", "api.target"
            )
            assertTrue(content.captured.startsWith("POST /users/17 HTTP/1.1\r\n"))
            assertTrue(content.captured.contains("Authorization: Bearer $canary\r\n"))
            assertTrue(content.captured.contains("Content-Length: 11\r\n"))
            assertTrue(content.captured.endsWith("{\"id\":\"17\"}"))
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `raw HTTP absolute URL selects HTTP and expands a path handle`() {
        val store = VariableStore().apply { put("user_id", "17", "user", "127.0.0.1") }
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            val service = mockk<HttpService>()
            val content = slot<String>()
            every { HttpService.httpService("127.0.0.1", 5000, false) } returns service
            every { HttpRequest.httpRequest(service, capture(content)) } returns mockk()
            store.expand(
                "GET http://127.0.0.1:5000/users/{{user_id}} HTTP/1.1\r\nHost: 127.0.0.1:5000\r\n\r\n",
                "user", "127.0.0.1"
            )
            assertTrue(content.captured.startsWith("GET /users/17 HTTP/1.1\r\n"))
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `bearer retry refuses a token bound to a different host`() {
        val store = VariableStore().apply { put("session", canary, "user", "api.target") }
        val service = mockk<HttpService>()
        val request = mockk<HttpRequest>()
        every { service.host() } returns "other.target"
        every { request.httpService() } returns service
        assertThrows(IllegalArgumentException::class.java) {
            store.withBearerToken(request, "session", "user", "api.target")
        }
        io.mockk.verify(exactly = 0) { request.withRemovedHeader(any<String>()) }
    }
}
