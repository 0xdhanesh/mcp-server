package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.sitemap.SiteMapFilter
import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.portswigger.mcp.KtorServerManager
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.TestSseMcpClient
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.TokenRequestProfile
import net.portswigger.mcp.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.*
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScopeSitemapIntegrationTest {
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val config = mockk<McpConfig>(relaxed = true)
    private val store = VariableStore()
    private val client = TestSseMcpClient()
    private val captured = CapturedHttpRequests()
    private val manager = KtorServerManager(api, captured, store)
    private val port = ServerSocket(0).use { it.localPort }
    private val approved = mutableSetOf("https://allowed.test/")
    private var enforce = true

    @BeforeEach fun start() {
        every { config.host } returns "127.0.0.1"
        every { config.port } returns port
        every { config.enforceBurpTargetScope } answers { enforce }
        every { config.configEditingTooling } returns true
        every { config.requireHttpRequestApproval } returns false
        every { config.requireDataAccessApproval } returns false
        every { config.tokenRequestProfile() } returns null
        every { api.burpSuite().version().edition() } returns BurpSuiteEdition.PROFESSIONAL
        every { api.scope().isInScope(any()) } answers { approved.any { firstArg<String>().startsWith(it) } }
        every { api.scope().includeInScope(any()) } answers { approved += firstArg<String>(); Unit }
        every { api.scope().excludeFromScope(any()) } answers { approved -= firstArg<String>(); Unit }
        mockkStatic(HttpService::class, HttpRequest::class, HttpHeader::class, SiteMapFilter::class)
        every { HttpService.httpService(any<String>(), any<Int>(), any<Boolean>()) } answers {
            service(firstArg(), secondArg(), thirdArg())
        }
        every { HttpRequest.httpRequest(any<HttpService>(), any<String>()) } answers { request(firstArg(), secondArg()) }
        every { HttpHeader.httpHeader(any<String>(), any<String>()) } answers {
            val name = firstArg<String>(); val value = secondArg<String>()
            mockk<HttpHeader> { every { name() } returns name; every { value() } returns value }
        }
        every { HttpRequest.http2Request(any<HttpService>(), any<List<HttpHeader>>(), any<String>()) } answers {
            request(firstArg(), "GET /api HTTP/1.1\r\nHost: blocked.test\r\n\r\n")
        }
        every { api.http().sendRequest(any<HttpRequest>()) } returns exchange(200)
        every { api.http().sendRequest(any<HttpRequest>(), any<burp.api.montoya.http.HttpMode>()) } returns exchange(200)
        val ready = CountDownLatch(1)
        var failed: Throwable? = null
        manager.start(config) {
            if (it is ServerState.Failed) { failed = it.exception; ready.countDown() }
            if (it is ServerState.Running) ready.countDown()
        }
        check(ready.await(15, TimeUnit.SECONDS))
        failed?.let { throw it }
        runBlocking { client.connectToServer("http://127.0.0.1:$port") }
    }

    @AfterEach fun stop() {
        runBlocking { if (client.isConnected()) client.close() }
        manager.shutdown()
        RepeaterSession.resetForTests()
        ToolResultRedaction.store = null
        unmockkStatic(HttpService::class, HttpRequest::class, HttpHeader::class, SiteMapFilter::class)
    }

    private fun call(name: String, args: Map<String, Any> = emptyMap()): CallToolResult = runBlocking {
        checkNotNull(client.callTool(name, args))
    }
    private fun text(result: CallToolResult) = (result.content.single() as TextContent).text
    private fun denied(result: CallToolResult) {
        assertTrue(result.isError == true, text(result))
        assertEquals("OUT_OF_SCOPE", Json.parseToJsonElement(text(result)).jsonObject["code"]!!.jsonPrimitive.content)
    }
    private fun sendArgs(host: String) = mapOf("content" to "GET /api HTTP/1.1\r\nHost: $host\r\n\r\n",
        "targetHostname" to host, "targetPort" to 443, "usesHttps" to true)

    @Test fun `scope tools are callable and changes match Montoya scope`() {
        val names = runBlocking { client.listTools() }.map { it.name }
        assertTrue(names.containsAll(listOf("get_scope_status", "include_in_scope", "exclude_from_scope", "list_sitemap", "get_sitemap_item", "get_scanner_issues")))
        val url = "https://new.test/app/"
        fun status() = Json.parseToJsonElement(text(call("get_scope_status", mapOf("url" to url)))).jsonObject["inScope"]!!.jsonPrimitive.boolean
        assertFalse(status())
        assertEquals("{\"ok\":true,\"url\":\"$url\"}", text(call("include_in_scope", mapOf("url" to url))))
        assertTrue(status())
        call("exclude_from_scope", mapOf("url" to url))
        assertFalse(status())
        every { config.configEditingTooling } returns false
        assertTrue(call("include_in_scope", mapOf("url" to url)).isError == true)
        assertFalse(status())
        assertTrue(call("get_scope_status", mapOf("url" to "file:///private")).isError == true)
        verify(exactly = 0) { api.http().sendRequest(any<HttpRequest>()) }
    }

    @Test fun `HTTP scope is enforced with approvals off or auto approved and off toggle restores sends`() {
        denied(call("send_http1_request", sendArgs("blocked.test")))
        every { config.requireHttpRequestApproval } returns true
        every { config.getAutoApproveTargetsList() } returns listOf("blocked.test")
        denied(call("send_http1_request", sendArgs("blocked.test")))
        every { config.requireHttpRequestApproval } returns false
        denied(call("send_http2_request", mapOf("pseudoHeaders" to mapOf("method" to "GET", "path" to "/api", "authority" to "blocked.test", "scheme" to "https"),
            "headers" to emptyMap<String, String>(), "requestBody" to "", "targetHostname" to "blocked.test", "targetPort" to 443, "usesHttps" to true)))
        verify(exactly = 0) { api.http().sendRequest(any<HttpRequest>()) }
        verify(exactly = 0) { api.http().sendRequest(any<HttpRequest>(), any<burp.api.montoya.http.HttpMode>()) }
        assertFalse(call("send_http1_request", sendArgs("allowed.test")).isError == true)
        enforce = false
        assertFalse(call("send_http1_request", sendArgs("blocked.test")).isError == true)
        verify(exactly = 2) { api.http().sendRequest(any<HttpRequest>()) }
    }

    @Test fun `variables are expanded before scope check and replay preflights every role`() {
        approved.clear(); approved += "https://allowed.test/public/"
        store.put("route", "private/secret-canary", "user", "allowed.test")
        store.put("route", "public/ok", "admin", "allowed.test")
        val template = "curl https://allowed.test/{{route}}"
        denied(call("send_with_vars", mapOf("request" to template, "identity" to "user")))
        denied(call("authz_replay", mapOf("id" to "boundary", "request" to template, "roles" to listOf("admin", "user"))))
        verify(exactly = 0) { api.http().sendRequest(any<HttpRequest>()) }
        assertFalse(ToolActivity.exportJsonl().contains("private/secret-canary"))
    }

    @Test fun `configured token endpoint and automatic acquisition cannot cross scope`() {
        val profile = TokenRequestProfile(template = "curl https://auth.test/token --data '{{password}}'", targetHost = "allowed.test")
        every { config.tokenRequestProfile() } returns profile
        store.put("password", "credential-canary", "user", "auth.test")
        denied(call("acquire_configured_session_var", mapOf("identity" to "user")))
        every { api.http().sendRequest(any<HttpRequest>()) } returns exchange(401)
        denied(call("send_http1_request", sendArgs("allowed.test") + ("allowTokenRequest" to true)))
        verify(exactly = 1) { api.http().sendRequest(any<HttpRequest>()) }
    }

    @Test fun `Repeater denies before UI send or API fallback`() {
        val raw = "GET /api HTTP/1.1\r\nHost: blocked.test\r\n\r\n"
        RepeaterSession.save(StoredRepeaterTab("blocked", request(service("blocked.test", 443, true), raw), burp.api.montoya.http.HttpMode.HTTP_1))
        denied(call("send_repeater_request", mapOf("tabName" to "blocked")))
        denied(call("send_repeater_request", mapOf("tabName" to "blocked", "issueFrom" to "http")))
        verify(exactly = 0) { api.userInterface() }
        verify(exactly = 0) { api.http().sendRequest(any<HttpRequest>(), any<burp.api.montoya.http.HttpMode>(), any<String>()) }
    }

    @Test fun `enforced Repeater sends use the checked API destination not the editable UI destination`() {
        val checked = request(service("allowed.test", 443, true), "GET /api HTTP/1.1\r\nHost: allowed.test\r\n\r\n")
        RepeaterSession.save(StoredRepeaterTab("checked", checked, burp.api.montoya.http.HttpMode.HTTP_1))
        every { api.http().sendRequest(checked, any<burp.api.montoya.http.HttpMode>(), any<String>()) } returns exchange(200)
        mockkObject(RepeaterUi)
        try {
            every { RepeaterUi.tryReadRequest(any(), any()) } returns null
            val result = call("send_repeater_request", mapOf("tabName" to "checked"))
            assertFalse(result.isError == true, text(result))
            assertTrue(text(result).contains("not shown in the Repeater response pane"), text(result))
            verify(exactly = 0) { RepeaterUi.trySend(any(), any(), any(), any(), any()) }
            verify(exactly = 1) { api.http().sendRequest(checked, any<burp.api.montoya.http.HttpMode>(), any<String>()) }
            verify(atLeast = 1) { checked.isInScope() }
        } finally { unmockkObject(RepeaterUi) }
    }

    @Test fun `history token acquisition and history or captured Repeater sends are fenced`() {
        val original = request(service("blocked.test", 443, true), "GET /token HTTP/1.1\r\nHost: blocked.test\r\n\r\n")
        val item = mockk<burp.api.montoya.proxy.ProxyHttpRequestResponse>(relaxed = true)
        every { item.id() } returns 17
        every { item.request() } returns original
        every { item.finalRequest() } returns original
        every { item.httpService() } returns original.httpService()
        every { api.proxy().history() } returns listOf(item)
        denied(call("acquire_session_var", mapOf("name" to "session", "identity" to "user", "historyId" to 17, "jsonPath" to "$.access_token")))
        denied(call("send_recorded_request_to_repeater", mapOf("source" to "proxy", "requestId" to 17)))
        captured.record(99, original, "EXTENSIONS")
        denied(call("send_recorded_request_to_repeater", mapOf("source" to "captured", "requestId" to 99)))
        // Copying to Repeater is local; sendNow is fenced in the shared Repeater sender.
        denied(call("send_proxy_history_to_repeater", mapOf("historyId" to 17, "sendNow" to true)))
        val http = api.http()
        verify(exactly = 0) { http.sendRequest(any<HttpRequest>()) }
    }

    @Test fun `scope is checked again on the automatic retry after token acquisition`() {
        approved += "https://auth.test/"
        store.put("password", "credential-canary", "user", "auth.test")
        every { config.tokenRequestProfile() } returns TokenRequestProfile(
            template = "curl https://auth.test/token --data '{{password}}'", targetHost = "allowed.test")
        val tokenResponse = exchange(200)
        val tokenBody = tokenResponse.response()
        every { tokenBody.bodyToString() } returns "{\"access_token\":\"new-token-canary\"}"
        val http = api.http()
        every { http.sendRequest(any<HttpRequest>()) } answers {
            if (firstArg<HttpRequest>().httpService().host() == "auth.test") {
                approved -= "https://allowed.test/"
                tokenResponse
            } else exchange(401)
        }
        val acquired = call("acquire_configured_session_var", mapOf("identity" to "user"))
        assertFalse(acquired.isError == true, text(acquired))
        approved += "https://allowed.test/"
        clearMocks(http, answers = false)
        denied(call("send_http1_request", sendArgs("allowed.test") + ("allowTokenRequest" to true)))
        verify(exactly = 2) { http.sendRequest(any<HttpRequest>()) }
    }

    @Test fun `local probe exemption cannot be used for custom target`() {
        call("call_extension_command", mapOf("header" to "X-Burp-Mcp-Discover", "command" to "tools"))
        denied(call("call_extension_command", mapOf("header" to "X-Burp-Mcp-Discover", "command" to "tools", "targetHostname" to "blocked.test", "targetPort" to 443, "usesHttps" to true)))
        verify(exactly = 1) { api.http().sendRequest(any<HttpRequest>()) }
    }

    @Test fun `site map prefix pagination and handle round trip are stable and redacted`() {
        val a = sitemapItem("/one", "password=unregistered-secret")
        val b = sitemapItem("/two", "another-body")
        every { api.siteMap().requestResponses() } returns listOf(b, a)
        val filter = mockk<SiteMapFilter>()
        every { SiteMapFilter.prefixFilter("https://allowed.test/one") } returns filter
        every { api.siteMap().requestResponses(filter) } returns listOf(a)
        fun page(offset: Int) = Json.decodeFromString<SiteMapPage>(text(call("list_sitemap", mapOf("offset" to offset, "limit" to 1))))
        val first = page(0)
        every { api.siteMap().requestResponses() } returns listOf(a, b)
        assertEquals(first, page(0))
        val second = page(first.nextOffset!!)
        assertEquals(null, second.nextOffset)
        assertNotEquals(first.items.single().id, second.items.single().id)
        assertEquals(2, first.total)
        assertFalse(text(call("list_sitemap")).contains("another-body"))
        val filtered = Json.decodeFromString<SiteMapPage>(text(call("list_sitemap", mapOf("prefix" to "https://allowed.test/one"))))
        assertEquals(1, filtered.total)
        val result = text(call("get_sitemap_item", mapOf("id" to filtered.items.single().id)))
        assertTrue(result.contains("GET /one"))
        assertFalse(result.contains("unregistered-secret"))
        assertFalse(result.contains("header-secret"))
        val huge = sitemapItem("/large", "body-canary".repeat(1000))
        every { api.siteMap().requestResponses() } returns listOf(huge, huge)
        val deduplicated = Json.decodeFromString<SiteMapPage>(text(call("list_sitemap")))
        assertEquals(1, deduplicated.total)
        val limited = text(call("get_sitemap_item", mapOf("id" to deduplicated.items.single().id)))
        assertTrue(limited.length <= 5000)
        assertEquals(deduplicated.items.single().id, Json.decodeFromString<SiteMapDetail>(limited).id)
        assertTrue(call("list_sitemap", mapOf("offset" to -1)).isError == true)
        every { api.siteMap().requestResponses() } returns emptyList()
        assertTrue(text(call("get_sitemap_item", mapOf("id" to filtered.items.single().id))).contains("SITEMAP_ITEM_NOT_FOUND"))
    }

    @Test fun `site map access denial happens before reading Burp data`() {
        every { config.requireDataAccessApproval } returns true
        every { config.alwaysAllowHttpHistory } returns false
        val previous = DataAccessSecurity.approvalHandler
        DataAccessSecurity.approvalHandler = object : DataAccessApprovalHandler {
            override suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean {
                assertEquals(DataAccessType.HTTP_HISTORY, accessType)
                return false
            }
        }
        try {
            assertTrue(call("list_sitemap").isError == true)
            assertTrue(call("get_sitemap_item", mapOf("id" to "sm-${"a".repeat(64)}")).isError == true)
            verify(exactly = 0) { api.siteMap() }
        } finally { DataAccessSecurity.approvalHandler = previous }
    }

    @Test fun `short stored values do not corrupt site map JSON keys or opaque handles`() {
        every { api.siteMap().requestResponses() } returns listOf(sitemapItem("/one", "private-canary"))
        val before = Json.decodeFromString<SiteMapPage>(text(call("list_sitemap")))
        store.put("short_secret", "a", "user", "allowed.test")
        val after = Json.decodeFromString<SiteMapPage>(text(call("list_sitemap")))
        assertEquals(before.items.single().id, after.items.single().id)
        val detail = Json.decodeFromString<SiteMapDetail>(text(call("get_sitemap_item", mapOf("id" to after.items.single().id))))
        assertEquals(after.items.single().id, detail.id)
        assertFalse(detail.response!!.contains("private-canary"))
    }

    private fun service(host: String, port: Int, secure: Boolean) = mockk<HttpService>().also {
        every { it.host() } returns host; every { it.port() } returns port; every { it.secure() } returns secure
    }
    private fun request(service: HttpService, raw: String): HttpRequest = mockk<HttpRequest>(relaxed = true).also {
        val path = raw.substringBefore("\r\n").split(' ')[1]
        val url = "${if (service.secure()) "https" else "http"}://${service.host()}$path"
        every { it.httpService() } returns service
        every { it.toString() } returns raw
        every { it.url() } returns url
        every { it.method() } returns "GET"
        every { it.path() } returns path
        every { it.isInScope() } answers { approved.any { prefix -> url.startsWith(prefix) } }
        every { it.toByteArray().length() } returns raw.toByteArray().size
        every { it.withRemovedHeader(any<String>()) } returns it
        every { it.withAddedHeader(any<String>(), any<String>()) } returns it
    }
    private fun exchange(status: Int): HttpRequestResponse {
        val response = mockk<HttpResponse>(relaxed = true).also {
            every { it.statusCode() } returns status.toShort()
            every { it.toString() } returns "HTTP/1.1 $status OK\r\n\r\nresponse"
        }
        return mockk<HttpRequestResponse>(relaxed = true).also {
            every { it.response() } returns response
            every { it.toString() } returns response.toString()
        }
    }
    private fun sitemapItem(path: String, body: String): HttpRequestResponse {
        val request = request(service("allowed.test", 443, true), "GET $path HTTP/1.1\r\nAuthorization: Bearer header-secret\r\n\r\n")
        val response = mockk<HttpResponse>(relaxed = true).also {
            every { it.statusCode() } returns 200
            every { it.toString() } returns "HTTP/1.1 200 OK\r\n\r\n$body"
            every { it.toByteArray().length() } returns body.length + 19
        }
        return mockk<HttpRequestResponse>(relaxed = true).also {
            every { it.request() } returns request
            every { it.response() } returns response
            every { it.httpService() } returns request.httpService()
            every { it.annotations().notes() } returns ""
        }
    }
}
