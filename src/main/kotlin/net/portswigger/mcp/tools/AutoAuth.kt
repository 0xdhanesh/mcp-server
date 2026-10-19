package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.readiness
import net.portswigger.mcp.security.RequestTemplate
import net.portswigger.mcp.security.VariableStore
import net.portswigger.mcp.security.TargetScopeSecurity
import net.portswigger.mcp.security.OutOfScopeException

internal data class AutoAuthOutcome(val exchange: HttpRequestResponse?, val note: String = "")

/** Retry one MCP HTTP send after a 401, using only the configured role and token request. */
internal fun sendMcpRequestWithAutoAuth(
    api: MontoyaApi, config: McpConfig, store: VariableStore,
    request: HttpRequest, mode: HttpMode? = null, identity: String? = null,
    initialExchange: HttpRequestResponse? = null, allowTokenRequest: Boolean = false
): AutoAuthOutcome {
    fun send(outgoing: HttpRequest): HttpRequestResponse? {
        TargetScopeSecurity.check(outgoing, config)
        return if (mode == null) api.http().sendRequest(outgoing) else api.http().sendRequest(outgoing, mode)
    }

    val first = initialExchange ?: send(request)
    if (runCatching { first?.response()?.statusCode()?.toInt() }.getOrNull() != 401) return AutoAuthOutcome(first)
    if (!allowTokenRequest) return AutoAuthOutcome(first,
        "MCP automatic auth skipped: allowTokenRequest was not true; returned original 401.\n")

    val profile = config.tokenRequestProfile()
        ?: return AutoAuthOutcome(first, "MCP automatic auth: no token request is saved; returned original 401.\n")
    val readiness = runCatching { profile.readiness(store) }.getOrNull()
        ?: return AutoAuthOutcome(first, "MCP automatic auth: saved token request is invalid; returned original 401.\n")
    val role = readiness.selectedRole(identity)
        ?: return AutoAuthOutcome(first, "MCP automatic auth: ${readiness.summary()} Returned original 401.\n")
    val missing = readiness.missingFor(role, store)
    if (missing.isNotEmpty()) {
        return AutoAuthOutcome(first,
            "MCP automatic auth: missing ${missing.joinToString(", ") { "{{$it}}" }} for role $role; returned original 401.\n")
    }
    val tokenRequest = runCatching { RequestTemplate.parse(profile.requestTemplate()) }.getOrNull()
        ?: return AutoAuthOutcome(first, "MCP automatic auth: saved token request is invalid; returned original 401.\n")
    val tokenHost = readiness.tokenUseHost
    val service = request.httpService()
    if (VariableStore.normalizeHost(service.host()) != VariableStore.normalizeHost(tokenHost)) {
        return AutoAuthOutcome(first, "MCP automatic auth: token use host differs; returned original 401.\n")
    }
    val tokenFirstLine = tokenRequest.raw.lineSequence().firstOrNull().orEmpty()
    val tokenMethod = tokenFirstLine.substringBefore(' ', "")
    val tokenPath = tokenFirstLine.substringAfter(' ', "").substringBefore(' ', "")
    val sameEndpoint = runCatching {
        VariableStore.normalizeHost(service.host()) == tokenRequest.host &&
            service.port() == tokenRequest.service.port() &&
            request.method().equals(tokenMethod, true) && request.path() == tokenPath
    }.getOrDefault(false)
    if (sameEndpoint) return AutoAuthOutcome(first, "MCP automatic auth: token endpoint is not retried.\n")

    val acquired = try {
        executeAcquireConfiguredSessionVar(role, api, config, store)
        true
    } catch (error: OutOfScopeException) {
        throw error
    } catch (_: Exception) {
        false
    }
    if (!acquired) {
        return AutoAuthOutcome(first, "MCP automatic auth: token acquisition failed; returned original 401.\n")
    }
    val retry = runCatching { store.withBearerToken(request, profile.variableName, role, tokenHost) }.getOrNull()
        ?: return AutoAuthOutcome(first, "MCP automatic auth: token could not be applied; returned original 401.\n")
    return AutoAuthOutcome(send(retry),
        "MCP automatic auth: acquired a token for $role and retried once. " +
            "Displayed {{name}} handles can mask actual request or response values; they do not mean placeholders were sent.\n")
}
