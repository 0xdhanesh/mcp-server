package net.portswigger.mcp.security

import burp.api.montoya.http.message.requests.HttpRequest
import net.portswigger.mcp.config.McpConfig

/** Check the fully constructed request, independently of HTTP approval settings. */
internal object TargetScopeSecurity {
    fun check(request: HttpRequest, config: McpConfig) {
        if (!config.enforceBurpTargetScope) return
        // Fail closed if Burp cannot determine scope; never include request data in errors.
        if (!runCatching { request.isInScope() }.getOrDefault(false)) throw OutOfScopeException()
    }
}

internal class OutOfScopeException : IllegalStateException("OUT_OF_SCOPE")
