package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.Http
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.TokenRequestProfile
import net.portswigger.mcp.security.VariableStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoAuthTest {
    private val token = "jwt.canary.private"

    @Test
    fun `HTTP tool arguments require explicit token request opt in`() {
        val http1 = """{"content":"GET /api/status HTTP/1.1","targetHostname":"api.target","targetPort":443,"usesHttps":true}"""
        val http2 = """{"pseudoHeaders":{},"headers":{},"requestBody":"","targetHostname":"api.target","targetPort":443,"usesHttps":true}"""
        val withVars = """{"request":"curl https://api.target/api/status"}"""
        assertFalse(Json.decodeFromString<SendHttp1Request>(http1).allowTokenRequest)
        assertFalse(Json.decodeFromString<SendHttp2Request>(http2).allowTokenRequest)
        assertFalse(Json.decodeFromString<SendWithVars>(withVars).allowTokenRequest)
        assertEquals(null, Json.decodeFromString<SendWithVars>(withVars).identity)
        assertTrue(Json.decodeFromString<SendHttp1Request>(http1.dropLast(1) + ",\"allowTokenRequest\":true}").allowTokenRequest)
    }

    @Test
    fun `MCP HTTP 401 infers the only ready role and retries exactly once`() {
        val store = VariableStore().apply {
            put("username", "example-user", "user", "auth.target")
            put("password", "example-password", "user", "auth.target")
        }
        val profile = TokenRequestProfile(
            template = "curl -s http://auth.target/token -H 'Content-Type: application/json' " +
                "--data '{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}'",
            targetHost = "api.target"
        )
        val config = mockk<McpConfig>()
        every { config.tokenRequestProfile() } returns profile
        every { config.requireHttpRequestApproval } returns false

        val apiService = mockk<HttpService>()
        every { apiService.host() } returns "api.target"
        every { apiService.port() } returns 443
        val authService = mockk<HttpService>()
        every { authService.host() } returns "auth.target"
        every { authService.port() } returns 80

        val original = mockk<HttpRequest>()
        val stripped = mockk<HttpRequest>()
        val retried = mockk<HttpRequest>()
        val login = mockk<HttpRequest>()
        every { original.httpService() } returns apiService
        every { original.method() } returns "GET"
        every { original.path() } returns "/api/status"
        every { original.withRemovedHeader("Authorization") } returns stripped
        every { stripped.withAddedHeader("Authorization", "Bearer $token") } returns retried

        val first = exchange(401, "{\"code\":\"TOKEN_MISSING\"}")
        val loginResult = exchange(200, "{\"access_token\":\"$token\"}")
        val second = exchange(200, "{\"ok\":true}")
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        every { api.http() } returns http
        every { http.sendRequest(original) } returns first
        every { http.sendRequest(login) } returns loginResult
        every { http.sendRequest(retried) } returns second

        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            every { HttpService.httpService("auth.target", 80, false) } returns authService
            every { HttpRequest.httpRequest(authService, any<String>()) } returns login
            val outcome = sendMcpRequestWithAutoAuth(api, config, store, original, allowTokenRequest = true)
            assertSame(second, outcome.exchange)
            assertTrue(outcome.note.contains("acquired a token for user and retried once"))
            assertFalse(outcome.note.contains(token))
            assertEquals("{{session}}", store.redact(token))

            val otherService = mockk<HttpService>()
            every { otherService.host() } returns "other.target"
            val otherRequest = mockk<HttpRequest>()
            every { otherRequest.httpService() } returns otherService
            every { http.sendRequest(otherRequest) } returns first
            val otherOutcome = sendMcpRequestWithAutoAuth(api, config, store, otherRequest,
                allowTokenRequest = true)
            assertSame(first, otherOutcome.exchange)
            assertTrue(otherOutcome.note.contains("token use host differs"))

            every { http.sendRequest(original, HttpMode.HTTP_2) } returns first
            every { http.sendRequest(retried, HttpMode.HTTP_2) } returns second
            val http2Outcome = sendMcpRequestWithAutoAuth(api, config, store, original, HttpMode.HTTP_2,
                allowTokenRequest = true)
            assertSame(second, http2Outcome.exchange)

            verify(exactly = 1) { http.sendRequest(original) }
            verify(exactly = 2) { http.sendRequest(login) }
            verify(exactly = 1) { http.sendRequest(retried) }
            verify(exactly = 1) { http.sendRequest(original, HttpMode.HTTP_2) }
            verify(exactly = 1) { http.sendRequest(retried, HttpMode.HTTP_2) }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `client credentials are sent to issuer and bearer token is retried on a different API host`() {
        val issuer = "token-providing.domain.com"
        val apiHost = "token-consuming.domain.com"
        val store = VariableStore().apply {
            put("client_id", "uat-client", "service", issuer)
            put("client_secret", "uat-secret", "service", issuer)
        }
        val profile = TokenRequestProfile(
            template = "curl -s -X POST https://$issuer/oauth/token " +
                "-d 'grant_type=client_credentials&client_id={{client_id}}&client_secret={{client_secret}}'",
            targetHost = apiHost
        )
        val config = mockk<McpConfig>()
        every { config.tokenRequestProfile() } returns profile
        every { config.requireHttpRequestApproval } returns false

        val issuerService = mockk<HttpService>()
        every { issuerService.host() } returns issuer
        every { issuerService.port() } returns 443
        val apiService = mockk<HttpService>()
        every { apiService.host() } returns apiHost
        every { apiService.port() } returns 443
        val original = mockk<HttpRequest>()
        val stripped = mockk<HttpRequest>()
        val retried = mockk<HttpRequest>()
        val tokenRequest = mockk<HttpRequest>()
        every { original.httpService() } returns apiService
        every { original.method() } returns "GET"
        every { original.path() } returns "/resource"
        every { original.withRemovedHeader("Authorization") } returns stripped
        every { stripped.withAddedHeader("Authorization", "Bearer $token") } returns retried

        val first = exchange(401, "{}")
        val issued = exchange(200, "{\"access_token\":\"$token\"}")
        val success = exchange(200, "{\"ok\":true}")
        val http = mockk<Http>()
        val api = mockk<MontoyaApi>()
        every { api.http() } returns http
        every { http.sendRequest(original) } returns first
        every { http.sendRequest(tokenRequest) } returns issued
        every { http.sendRequest(retried) } returns success

        val issuerRequests = mutableListOf<String>()
        mockkStatic(HttpService::class)
        mockkStatic(HttpRequest::class)
        try {
            every { HttpService.httpService(issuer, 443, true) } returns issuerService
            every { HttpRequest.httpRequest(issuerService, any<String>()) } answers {
                issuerRequests += secondArg<String>()
                tokenRequest
            }
            val outcome = sendMcpRequestWithAutoAuth(api, config, store, original,
                allowTokenRequest = true)

            assertSame(success, outcome.exchange)
            assertEquals(1, issuerRequests.size)
            assertTrue(issuerRequests.single().startsWith("POST /oauth/token HTTP/1.1\r\nHost: $issuer\r\n"))
            assertTrue(issuerRequests.single().contains("Content-Type: application/x-www-form-urlencoded\r\n"))
            assertTrue(issuerRequests.single().contains("grant_type=client_credentials&client_id=uat-client&client_secret=uat-secret"))
            assertEquals(apiHost, store.listNames().single { it.name == "session" }.host)
            assertFalse(store.listNames().any { it.name == "session" && it.host == issuer })
            verify(exactly = 1) { http.sendRequest(original) }
            verify(exactly = 1) { http.sendRequest(tokenRequest) }
            verify(exactly = 1) { http.sendRequest(retried) }
        } finally {
            unmockkStatic(HttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `no ready role leaves the original 401 and makes no login request`() {
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val request = mockk<HttpRequest>()
        val first = exchange(401, "{}")
        val config = mockk<McpConfig>()
        every { api.http() } returns http
        every { http.sendRequest(request) } returns first
        every { config.tokenRequestProfile() } returns TokenRequestProfile(
            template = "curl http://auth.target/token --data-raw '{{username}}'"
        )
        mockkStatic(HttpService::class)
        try {
            every { HttpService.httpService("auth.target", 80, false) } returns mockk()
            val outcome = sendMcpRequestWithAutoAuth(api, config, VariableStore(), request,
                allowTokenRequest = true)
            assertSame(first, outcome.exchange)
            assertTrue(outcome.note.contains("No ready role"))
            verify(exactly = 1) { http.sendRequest(request) }
        } finally {
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `HTTP2 send also preserves the original 401 when auto auth is off`() {
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val request = mockk<HttpRequest>()
        val first = exchange(401, "{}")
        val config = mockk<McpConfig>()
        every { api.http() } returns http
        every { http.sendRequest(request, HttpMode.HTTP_2) } returns first
        every { config.tokenRequestProfile() } returns null

        val outcome = sendMcpRequestWithAutoAuth(api, config, VariableStore(), request, HttpMode.HTTP_2,
            allowTokenRequest = true)
        assertSame(first, outcome.exchange)
        assertTrue(outcome.note.contains("no token request is saved"))
        verify(exactly = 1) { http.sendRequest(request, HttpMode.HTTP_2) }
    }

    @Test
    fun `saved token request never runs without explicit opt in`() {
        val api = mockk<MontoyaApi>()
        val http = mockk<Http>()
        val request = mockk<HttpRequest>()
        val first = exchange(401, "{\"code\":\"TOKEN_MISSING\"}")
        val config = mockk<McpConfig>()
        every { api.http() } returns http
        every { http.sendRequest(request) } returns first
        every { http.sendRequest(request, HttpMode.HTTP_2) } returns first
        val outcome = sendMcpRequestWithAutoAuth(api, config, VariableStore(), request)
        val http2Outcome = sendMcpRequestWithAutoAuth(api, config, VariableStore(), request, HttpMode.HTTP_2)
        assertSame(first, outcome.exchange)
        assertSame(first, http2Outcome.exchange)
        assertTrue(outcome.note.contains("allowTokenRequest was not true"))
        verify(exactly = 0) { config.tokenRequestProfile() }
        verify(exactly = 1) { http.sendRequest(request) }
        verify(exactly = 1) { http.sendRequest(request, HttpMode.HTTP_2) }
    }

    private fun exchange(status: Int, body: String): HttpRequestResponse {
        val response = mockk<HttpResponse>()
        every { response.statusCode() } returns status.toShort()
        every { response.bodyToString() } returns body
        every { response.toString() } returns "HTTP/1.1 $status\r\n\r\n$body"
        return mockk<HttpRequestResponse>().also {
            every { it.response() } returns response
        }
    }
}
