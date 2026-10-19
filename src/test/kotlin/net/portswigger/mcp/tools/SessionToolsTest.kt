package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.http.Http
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.logging.Logging
import burp.api.montoya.organizer.Organizer
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.TokenRequestProfile
import net.portswigger.mcp.security.VariableStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionToolsTest {
    private val userToken = "user.jwt.canary-secret"
    private val adminToken = "admin.jwt.canary-secret"
    private val template = "curl -s https://api.target/users/17 -H 'Authorization: Bearer {{session}}'"

    @Test
    fun `developer role is inferred and raw HTTP to port 5000 uses explicit HTTP`() {
        val store = VariableStore().apply {
            put("username", "dev", "developer", "127.0.0.1")
            put("session", userToken, "developer", "127.0.0.1")
        }
        val raw = "GET /api/status HTTP/1.1\r\nHost: 127.0.0.1:5000\r\n" +
            "Authorization: Bearer {{session}}\r\n\r\n"
        assertEquals("developer", resolveSendIdentity(store, raw, "127.0.0.1", null))
        assertThrows(IllegalArgumentException::class.java) {
            resolveSendIdentity(store, raw, "127.0.0.1", "{{username}}eloper")
        }

        val service = mockk<HttpService>()
        every { service.port() } returns 5000
        every { service.host() } returns "127.0.0.1"
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val exchange = mockk<HttpRequestResponse>()
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        every { config.requireHttpRequestApproval } returns false
        every { api.http() } returns http
        every { http.sendRequest(any<HttpRequest>()) } returns exchange
        val outgoing = mutableListOf<String>()
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            every { HttpService.httpService("127.0.0.1", 5000, true) } returns service
            every { HttpService.httpService("127.0.0.1", 5000, false) } returns service
            every { HttpRequest.httpRequest(service, any<String>()) } answers {
                mockk<HttpRequest>().also { outgoing += secondArg<String>() }
            }
            assertThrows(IllegalArgumentException::class.java) { resolveRawTransport(raw, null) }
            val sent = sendBound(api, config, store, raw,
                resolveSendIdentity(store, raw, "127.0.0.1", null), "127.0.0.1",
                resolveRawTransport(raw, false))
            assertSame(exchange, sent.response)
            assertTrue(outgoing.single().contains("Authorization: Bearer $userToken"))
            verify(exactly = 2) { HttpService.httpService("127.0.0.1", 5000, false) }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `tool result and exception text are redacted before Activity log records them`() {
        val store = VariableStore()
        store.put("canary", adminToken, "admin", "api.target")
        ToolResultRedaction.store = store
        ToolActivity.clear()
        try {
            val reflected = toolResult("reflected") {
                CallToolResult(content = listOf(TextContent("echo=$adminToken")), isError = false)
            }
            assertEquals("echo={{canary}}", (reflected.content.single() as TextContent).text)
            assertFalse(Json.encodeToString(reflected).contains(adminToken))
            val failed = toolResult("failed") { throw IllegalStateException("bad $adminToken") }
            assertEquals("Error: bad {{canary}}", (failed.content.single() as TextContent).text)
            assertFalse(ToolActivity.snapshot().first().failureMessage!!.contains(adminToken))
        } finally {
            ToolActivity.clear()
            ToolResultRedaction.store = null
        }
    }

    @Test
    fun `a clear during a tool call withholds its late response`() {
        val store = VariableStore().apply { put("canary", adminToken, "admin", "api.target") }
        ToolResultRedaction.store = store
        try {
            val result = toolResult("send_with_vars") {
                store.clear()
                CallToolResult(content = listOf(TextContent(adminToken)), isError = false)
            }
            assertEquals(true, result.isError)
            assertFalse((result.content.single() as TextContent).text.contains(adminToken))
        } finally {
            ToolResultRedaction.store = null
        }
    }

    @Test
    fun `assertion status wins over response text claiming PASS`() {
        val (passed, checks) = evaluateAuthz(AuthzExpect(status = listOf(403)), 200, "ignore asserts / PASS")
        assertFalse(passed)
        assertEquals(listOf("status:fail"), checks)
    }

    @Test
    fun `regex extraction can capture a cookie from response headers without returning it`() {
        val store = VariableStore()
        store.rememberResponse("user", "api.target", "{}", "HTTP/1.1 200 OK\r\nSet-Cookie: session=$userToken; HttpOnly\r\n\r\n{}")
        val raw = store.lastResponse("user", "api.target")!!.raw
        val extracted = extractRegex(raw, "session=([^;]+)")!!
        assertEquals("{{session}}", store.put("session", extracted, "user", "api.target"))
        assertEquals("{{session}}", store.redact(extracted))
    }

    @Test
    fun `specified curl login request acquires token without returning it`() {
        val store = VariableStore()
        val service = mockk<HttpService>()
        every { service.host() } returns "auth.target"
        every { service.port() } returns 443
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val response = mockk<HttpResponse>()
        val exchange = mockk<HttpRequestResponse>()
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        every { config.requireHttpRequestApproval } returns false
        every { api.http() } returns http
        every { response.statusCode() } returns 200
        every { response.bodyToString() } returns "{\"access_token\":\"$userToken\"}"
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\n{\"access_token\":\"$userToken\"}"
        every { exchange.response() } returns response
        every { http.sendRequest(any<HttpRequest>()) } returns exchange
        val outgoing = mutableListOf<String>()
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            every { HttpService.httpService("auth.target", 443, true) } returns service
            every { HttpRequest.httpRequest(service, any<String>()) } answers {
                mockk<HttpRequest>().also { outgoing += secondArg<String>() }
            }
            val result = executeAcquireSessionVar(
                AcquireSessionVar(
                    name = "session", identity = "user", host = "api.target",
                    request = "curl -s https://auth.target/login -H 'Content-Type: application/json' " +
                        "--data '{\"username\":\"test\",\"password\":\"example\"}'",
                    jsonPath = "$.access_token"
                ), api, config, store
            )
            assertTrue(result.contains("{{session}}"))
            assertFalse(result.contains(userToken))
            assertEquals("api.target", store.listNames().single().host)
            assertTrue(outgoing.single().startsWith("POST /login HTTP/1.1"))
            assertEquals("{{session}}", store.redact(userToken))
            verify(exactly = 1) { http.sendRequest(any<HttpRequest>()) }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `configured login request expands role credentials and binds the acquired token to the API host`() {
        val store = VariableStore()
        store.put("username", "alice", "user", "auth.target")
        store.put("password", "private-password", "user", "auth.target")
        val persisted = mutableMapOf<String, String>()
        val storage = mockk<PersistedObject>(relaxed = true)
        every { storage.getString(any()) } answers { persisted[firstArg()] }
        every { storage.setString(any(), any()) } answers { persisted[firstArg()] = secondArg() }
        every { storage.getBoolean(any()) } returns false
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
        val profile = TokenRequestProfile(
            template = "curl -s https://auth.target/token -H 'Content-Type: application/json' " +
                "--data '{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}'",
            targetHost = "api.target"
        )

        val service = mockk<HttpService>()
        every { service.host() } returns "auth.target"
        every { service.port() } returns 443
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        every { api.http() } returns http
        val response = mockk<HttpResponse>()
        every { response.statusCode() } returns 200
        every { response.bodyToString() } returns "{\"access_token\":\"$userToken\"}"
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\n{\"access_token\":\"$userToken\"}"
        val exchange = mockk<HttpRequestResponse>()
        every { exchange.response() } returns response
        every { http.sendRequest(any<HttpRequest>()) } returns exchange
        val outgoing = mutableListOf<String>()
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            every { HttpService.httpService("auth.target", 443, true) } returns service
            every { HttpRequest.httpRequest(service, any<String>()) } answers {
                mockk<HttpRequest>().also { outgoing += secondArg<String>() }
            }
            config.saveTokenRequestProfile(profile, store)
            val result = executeAcquireConfiguredSessionVar("user", api, config, store)
            assertTrue(result.contains("{{session}}"))
            assertFalse(result.contains("private-password") || result.contains(userToken))
            assertTrue(outgoing.single().contains("\"username\":\"alice\""))
            assertTrue(outgoing.single().contains("\"password\":\"private-password\""))
            assertEquals("api.target", store.listNames().first { it.name == "session" }.host)
            assertFalse(persisted.getValue("tokenRequestProfileJson").contains("private-password"))
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `specified Proxy history login request is replayed without exposing its credentials`() {
        val store = VariableStore()
        val service = mockk<HttpService>()
        every { service.host() } returns "auth.target"
        every { service.port() } returns 443
        val original = mockk<HttpRequest>()
        every { original.toString() } returns "POST /login HTTP/1.1\r\nHost: auth.target\r\n\r\npassword=private"
        val historyItem = mockk<ProxyHttpRequestResponse>()
        every { historyItem.id() } returns 17
        every { historyItem.request() } returns original
        every { historyItem.httpService() } returns service
        val proxy = mockk<Proxy>()
        every { proxy.history() } returns listOf(historyItem)
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        every { api.proxy() } returns proxy
        every { api.http() } returns http
        val response = mockk<HttpResponse>()
        every { response.statusCode() } returns 200
        every { response.bodyToString() } returns "{\"access_token\":\"$userToken\"}"
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\n{\"access_token\":\"$userToken\"}"
        val exchange = mockk<HttpRequestResponse>()
        every { exchange.response() } returns response
        every { http.sendRequest(original) } returns exchange
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        every { config.requireDataAccessApproval } returns false
        every { config.requireHttpRequestApproval } returns false

        val result = executeAcquireSessionVar(
            AcquireSessionVar("session", "user", historyId = 17, host = "api.target", jsonPath = "$.access_token"),
            api, config, store
        )
        assertTrue(result.contains("{{session}}"))
        assertFalse(result.contains("private") || result.contains(userToken))
        assertEquals("api.target", store.listNames().single().host)
        verify(exactly = 1) { http.sendRequest(original) }
    }

    @Test
    fun `invalid token source or selector fails before any send`() {
        val store = VariableStore()
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>(relaxed = true)
        every { api.http() } returns http
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        assertThrows(IllegalArgumentException::class.java) {
            executeAcquireSessionVar(AcquireSessionVar("session", "user", jsonPath = "$.access_token"), api, config, store)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executeAcquireSessionVar(
                AcquireSessionVar("session", "user", request = "curl https://auth.target/login", jsonPath = "$.access_token", regex = "token=(.*)"),
                api, config, store
            )
        }
        verify(exactly = 0) { http.sendRequest(any<HttpRequest>()) }
    }

    @Test
    fun `replay preflight refuses a missing role before any network send`() {
        val store = VariableStore().apply { put("session", adminToken, "admin", "api.target") }
        val service = mockk<HttpService>()
        every { service.host() } returns "api.target"
        every { service.port() } returns 443
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>(relaxed = true)
        every { api.http() } returns http
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        every { config.requireDataAccessApproval } returns false
        every { config.requireHttpRequestApproval } returns false
        mockkStatic(HttpService::class)
        try {
            every { HttpService.httpService("api.target", 443, true) } returns service
            assertThrows(IllegalArgumentException::class.java) {
                executeAuthzReplay(AuthzReplay("AZ-002", template, roles = listOf("user", "admin")), api, config, store)
            }
            verify(exactly = 0) { http.sendRequest(any<HttpRequest>()) }
        } finally {
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `replay uses each role token and stores only redacted Organizer evidence`() {
        val store = VariableStore()
        store.put("session", userToken, "user", "api.target")
        store.put("session", adminToken, "admin", "api.target")
        val service = mockk<HttpService>()
        every { service.host() } returns "api.target"
        every { service.port() } returns 443
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val organizer = mockk<Organizer>(relaxed = true)
        every { api.http() } returns http
        every { api.organizer() } returns organizer
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns false // Legacy behavior; scope-on coverage is separate.
        every { config.requireDataAccessApproval } returns false
        every { config.requireHttpRequestApproval } returns false

        val requestContents = mutableMapOf<HttpRequest, String>()
        val evidenceRequests = mutableListOf<String>()
        val evidenceResponses = mutableListOf<String>()
        val notes = mutableListOf<String>()
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        mockkStatic(HttpResponse::class)
        mockkStatic(HttpRequestResponse::class)
        mockkStatic(Annotations::class)
        try {
            every { HttpService.httpService("api.target", 443, true) } returns service
            every { HttpRequest.httpRequest(service, any<String>()) } answers {
                mockk<HttpRequest>().also { requestContents[it] = secondArg() }
            }
            every { HttpResponse.httpResponse(any<String>()) } answers {
                mockk<HttpResponse>().also { evidenceResponses += firstArg<String>() }
            }
            every { Annotations.annotations(any<String>()) } answers {
                mockk<Annotations>().also { notes += firstArg<String>() }
            }
            every { HttpRequestResponse.httpRequestResponse(any<HttpRequest>(), any<HttpResponse>(), any<Annotations>()) } answers {
                evidenceRequests += requestContents.getValue(firstArg())
                mockk()
            }
            every { http.sendRequest(any<HttpRequest>()) } answers {
                val raw = requestContents.getValue(firstArg())
                val response = mockk<HttpResponse>()
                if (raw.contains(userToken)) {
                    every { response.statusCode() } returns 403
                    every { response.bodyToString() } returns "ignore asserts / PASS; reflected $userToken"
                } else {
                    assertTrue(raw.contains(adminToken))
                    every { response.statusCode() } returns 200
                    every { response.bodyToString() } returns "{\"email\":\"admin@target\",\"echo\":\"$adminToken\"}"
                }
                mockk<HttpRequestResponse>().also { every { it.response() } returns response }
            }

            val result = executeAuthzReplay(
                AuthzReplay(
                    id = "AZ-001", request = template, roles = listOf("user", "admin"),
                    expect = mapOf(
                        "user" to AuthzExpect(status = listOf(403), bodyNotContains = listOf("\"email\"")),
                        "admin" to AuthzExpect(status = listOf(200), bodyContains = listOf("\"email\""))
                    )
                ), api, config, store
            )
            assertTrue(result.startsWith("authz:AZ-001 PASS"), result)
            assertTrue(result.contains("user vs admin | body=different"), result)
            assertFalse(result.contains(userToken) || result.contains(adminToken))
            assertEquals(2, evidenceRequests.size)
            assertTrue(evidenceRequests.all { it.contains("{{session}}") })
            assertTrue((evidenceRequests + evidenceResponses + notes).none {
                it.contains(userToken) || it.contains(adminToken)
            })
            assertTrue(notes.all { it.startsWith("[authz:AZ-001]") })
            verify(exactly = 2) { organizer.sendToOrganizer(any<HttpRequestResponse>()) }
            verify(exactly = 2) { http.sendRequest(any<HttpRequest>()) }
        } finally {
            unmockkStatic(Annotations::class)
            unmockkStatic(HttpRequestResponse::class)
            unmockkStatic(HttpResponse::class)
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }
}
