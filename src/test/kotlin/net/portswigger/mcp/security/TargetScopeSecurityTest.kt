package net.portswigger.mcp.security

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.*
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TargetScopeSecurityTest {
    @Test fun `expanded request scope is authoritative even if unexpanded host was approved`() {
        val api = mockk<burp.api.montoya.MontoyaApi>()
        val config = mockk<McpConfig>()
        every { config.enforceBurpTargetScope } returns true
        val store = mockk<VariableStore>()
        val template = "curl https://allowed.test/{{route}}"
        val expanded = mockk<HttpRequest>()
        every { expanded.isInScope() } returns false
        every { expanded.url() } returns "https://outside.test/private"
        every { store.redact(template) } returns template
        every { store.expand(template, "user", "allowed.test", null) } returns expanded
        mockkStatic(burp.api.montoya.http.HttpService::class)
        try {
            every { burp.api.montoya.http.HttpService.httpService("allowed.test", 443, true) } returns mockk()
            assertThrows(OutOfScopeException::class.java) {
                net.portswigger.mcp.tools.sendBound(api, config, store, template, "user", "allowed.test")
            }
            verify { expanded.isInScope() }
            verify { api wasNot Called }
            verify(exactly = 0) { config.requireHttpRequestApproval }
        } finally { unmockkStatic(burp.api.montoya.http.HttpService::class) }
    }

    @Test fun `enforcement defaults on persists and is independent of approvals`() {
        var stored: Boolean? = null
        val storage = mockk<PersistedObject>(relaxed = true)
        every { storage.getBoolean("enforceBurpTargetScope") } answers { stored }
        every { storage.setBoolean("enforceBurpTargetScope", any()) } answers { stored = secondArg() }
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
        val request = mockk<HttpRequest>()
        every { request.isInScope() } returns false
        assertTrue(config.enforceBurpTargetScope)
        assertThrows(OutOfScopeException::class.java) { TargetScopeSecurity.check(request, config) }
        config.enforceBurpTargetScope = false
        assertFalse(McpConfig(storage, mockk(relaxed = true)).enforceBurpTargetScope)
        clearMocks(request)
        assertDoesNotThrow { TargetScopeSecurity.check(request, config) }
        verify { request wasNot Called }
        config.enforceBurpTargetScope = true
        every { request.isInScope() } returns true
        assertDoesNotThrow { TargetScopeSecurity.check(request, config) }
        every { request.isInScope() } throws IllegalStateException("secret in provider error")
        assertEquals("OUT_OF_SCOPE", assertThrows(OutOfScopeException::class.java) { TargetScopeSecurity.check(request, config) }.message)
    }
}
