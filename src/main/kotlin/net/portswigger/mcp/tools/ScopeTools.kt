package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import java.net.URI

@Serializable data class GetScopeStatus(val url: String)
@Serializable data class IncludeInScope(val url: String)
@Serializable data class ExcludeFromScope(val url: String)
@Serializable private data class ScopeStatus(val inScope: Boolean, val url: String)
@Serializable private data class ScopeChange(val ok: Boolean, val url: String)

internal fun registerScopeUrl(url: String): String {
    val parsed = runCatching { URI(url) }.getOrNull()
    require(parsed != null && parsed.scheme?.lowercase() in setOf("http", "https") &&
        !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null && parsed.rawFragment == null &&
        (parsed.port == -1 || parsed.port in 1..65535)) {
        "Supply an absolute HTTP(S) URL without userinfo or fragment."
    }
    requireNoStoredSecret(url)
    require(McpLogSanitizer.text(url, null) == url) { "Supply a URL without credential values." }
    return url
}

internal fun Server.registerScopeTools(api: MontoyaApi, config: McpConfig) {
    mcpTool<GetScopeStatus>("Check Burp Target scope for an absolute HTTP(S) URL (include scheme and path). No request is sent.") {
        val checked = registerScopeUrl(url)
        Json.encodeToString(ScopeStatus(api.scope().isInScope(checked), checked))
    }
    mcpTool<IncludeInScope>("Include an agreed HTTP(S) URL/prefix in Burp Target scope. Requires Enable tools that can edit your config. Never expand scope merely to retry OUT_OF_SCOPE.") {
        check(config.configEditingTooling) { "CONFIG_EDITING_DISABLED: Enable tools that can edit your config to change Target scope." }
        val checked = registerScopeUrl(url)
        api.scope().includeInScope(checked)
        Json.encodeToString(ScopeChange(true, checked))
    }
    mcpTool<ExcludeFromScope>("Exclude an HTTP(S) URL/prefix from Burp Target scope. Requires Enable tools that can edit your config.") {
        check(config.configEditingTooling) { "CONFIG_EDITING_DISABLED: Enable tools that can edit your config to change Target scope." }
        val checked = registerScopeUrl(url)
        api.scope().excludeFromScope(checked)
        Json.encodeToString(ScopeChange(true, checked))
    }
}
