package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.TokenSelectorMode
import net.portswigger.mcp.config.readiness
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.RequestTemplate
import net.portswigger.mcp.security.VariableStore

@Serializable
data class SetSessionVar(val name: String, val value: String, val identity: String, val host: String)

@Serializable
data class ExtractSessionVar(
    val name: String, val identity: String, val host: String,
    val source: String = "last_send", val id: Int? = null,
    val jsonPath: String? = null, val regex: String? = null
)

@Serializable
data class AcquireSessionVar(
    val name: String, val identity: String,
    val request: String? = null, val historyId: Int? = null,
    val host: String? = null, val jsonPath: String? = null, val regex: String? = null
)

@Serializable
data class AcquireConfiguredSessionVar(val identity: String)

@Serializable
data class ListSessionVars(val identity: String? = null)

@Serializable
data class ClearSessionVars(val identity: String? = null)

@Serializable
data class SendWithVars(
    val request: String, val identity: String? = null, val host: String? = null,
    val allowTokenRequest: Boolean = false, val usesHttps: Boolean? = null
)

@Serializable
data class AuthzExpect(
    val status: List<Int>? = null,
    val bodyContains: List<String> = emptyList(),
    val bodyNotContains: List<String> = emptyList(),
    val jsonPath: Map<String, String> = emptyMap()
)

@Serializable
data class AuthzReplay(
    val id: String,
    val request: String? = null,
    val historyId: Int? = null,
    val roles: List<String>,
    val expect: Map<String, AuthzExpect>? = null,
    val extract: Map<String, String> = emptyMap(),
    val host: String? = null
)

internal class BoundResponse(val response: HttpRequestResponse, val elapsedMs: Long) {
    override fun toString(): String = "<bound HTTP response>"
}

internal fun Server.registerSessionTools(
    api: MontoyaApi, config: McpConfig, store: VariableStore
) {
    mcpTool<SetSessionVar>("Store a session value in memory for one identity and host. Returns only its handle.") {
        val handle = store.put(name, value, identity, host)
        "Stored $handle for $identity at ${VariableStore.normalizeHost(host)}."
    }

    mcpTool<ExtractSessionVar>(
        "Extract a session value from the last send, Proxy history ID, or Organizer ID using JSONPath or regex. " +
            "The extracted value is stored without appearing in the tool result."
    ) {
        require((jsonPath == null) != (regex == null)) { "Supply exactly one of jsonPath or regex." }
        val selectedHost = VariableStore.normalizeHost(host)
        val selected = when (source.lowercase()) {
            "last_send" -> store.lastResponse(identity, selectedHost)
            "history" -> {
                require(id != null) { "History ID required." }
                require(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
                    "HTTP history access denied by Burp Suite."
                }
                val item = findProxyHistoryItem(api, id) ?: throw IllegalArgumentException("History item unavailable.")
                require(item.httpService().host().equals(selectedHost, true)) { "Response host mismatch." }
                item.response()?.let { VariableStore.ResponseSnapshot(it.bodyToString(), it.toString()) }
            }
            "organizer" -> {
                require(id != null) { "Organizer ID required." }
                require(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.ORGANIZER, config) }) {
                    "Organizer access denied by Burp Suite."
                }
                val item = api.organizer().items().firstOrNull { it.id() == id }
                    ?: throw IllegalArgumentException("Organizer item unavailable.")
                require(item.httpService().host().equals(selectedHost, true)) { "Response host mismatch." }
                item.response()?.let { VariableStore.ResponseSnapshot(it.bodyToString(), it.toString()) }
            }
            else -> throw IllegalArgumentException("Unknown extraction source.")
        } ?: throw IllegalArgumentException("Response unavailable.")
        val value = if (jsonPath != null) extractJsonPath(selected.body, jsonPath) else extractRegex(selected.raw, regex!!)
        require(!value.isNullOrEmpty()) { "Extraction found no value." }
        "Stored ${store.put(name, value, identity, selectedHost)} for $identity at $selectedHost."
    }

    mcpTool<AcquireSessionVar>(
        "Send a specified token request (raw HTTP/curl or Proxy history ID), extract a response value with JSONPath " +
            "or regex, and store it for one identity and target host. Returns only the variable handle, never the token. " +
            "Use a history ID to keep login credentials out of MCP arguments."
    ) {
        executeAcquireSessionVar(this, api, config, store)
    }

    mcpTool<AcquireConfiguredSessionVar>(
        "Run the token request saved in the MCP tab for the given identity, extract its token, and return only a variable handle."
    ) {
        executeAcquireConfiguredSessionVar(identity, api, config, store)
    }

    mcpTool(
        "get_session_auth_status",
        "Check whether the saved token request can run after a 401. Returns only handle names, role names, and hosts; does not send any HTTP request."
    ) {
        val profile = config.tokenRequestProfile()
            ?: return@mcpTool "No token request is saved in the MCP tab."
        val state = runCatching { profile.readiness(store) }.getOrNull()
            ?: return@mcpTool "Saved token request is invalid. Check it in the MCP tab."
        "tokenRequestHost=${state.requestHost} tokenUseHost=${state.tokenUseHost} " +
            "required=${state.requiredNames.sorted().joinToString(",") { "{{$it}}" }.ifEmpty { "<none>" }} " +
            "readyRoles=${state.readyRoles.joinToString(",").ifEmpty { "<none>" }} " +
            "selectedRole=${state.selectedRole() ?: "<none>"}\n${state.summary()} " +
            "HTTP sends call the token endpoint only with allowTokenRequest=true. " +
            "Displayed {{name}} handles may be redaction of stored values, including role names."
    }

    mcpTool<ListSessionVars>("List stored variable names, identities, and hosts without values.") {
        val names = store.listNames().filter { identity == null || it.identity == identity }
        if (names.isEmpty()) "No session variables." else names.joinToString("\n") {
            "${store.getHandle(it.name)} identity=${it.identity} host=${it.host}"
        }
    }

    mcpTool<ClearSessionVars>("Clear all session variables, or those for one identity.") {
        store.clear(identity)
        if (identity == null) "Cleared all session variables." else "Cleared session variables for $identity."
    }

    mcpTool<SendWithVars>(
        "Send a raw HTTP/1.x request or curl template with {{name}} handles. Identity can be omitted " +
            "when exactly one stored role has every required handle for the request host. For raw HTTP " +
            "to a nonstandard port, set usesHttps=false for HTTP or true for HTTPS; curl uses its URL. " +
            "By default, never calls the saved token endpoint. Set allowTokenRequest=true to refresh " +
            "this identity after HTTP 401 and retry once. " +
            "The reply is redacted before it reaches the MCP client."
    ) {
        val transport = resolveRawTransport(request, usesHttps)
        val target = RequestTemplate.parse(request, transport)
        val selectedHost = host?.let(VariableStore::normalizeHost) ?: target.host
        val selectedIdentity = resolveSendIdentity(store, target.raw, selectedHost, identity)
        val started = System.nanoTime()
        val initial = sendBound(api, config, store, request, selectedIdentity, selectedHost, transport)
        val outcome = sendMcpRequestWithAutoAuth(
            api, config, store, store.expand(request, selectedIdentity, selectedHost, transport),
            identity = selectedIdentity, initialExchange = initial.response,
            allowTokenRequest = allowTokenRequest
        )
        val response = outcome.exchange?.response() ?: throw IllegalStateException("No HTTP response.")
        store.rememberResponse(selectedIdentity, selectedHost, response.bodyToString(), response.toString())
        val roleNote = if (identity == null) "MCP selected the only role with all required handles.\n" else ""
        "${roleNote}${outcome.note}status=${response.statusCode()} elapsedMs=${(System.nanoTime() - started) / 1_000_000}\n" +
            store.redact(response.bodyToString())
    }

    mcpTool<AuthzReplay>(
        "Replay one request template for several role names, evaluate assertions in Kotlin, and save redacted Organizer evidence. " +
            "PASS requires all supplied role expectations, or default admin success and lower-role denial."
    ) {
        executeAuthzReplay(this, api, config, store)
    }
}

internal fun executeAcquireConfiguredSessionVar(
    identity: String, api: MontoyaApi, config: McpConfig, store: VariableStore
): String {
    val profile = config.tokenRequestProfile() ?: throw IllegalArgumentException("No token request is configured in the MCP tab.")
    profile.validateForPersistence(store)
    return executeAcquireSessionVar(
        AcquireSessionVar(
            name = profile.variableName, identity = identity, request = profile.requestTemplate(),
            host = profile.targetHost.ifBlank { null },
            jsonPath = profile.selector.takeIf { profile.selectorMode == TokenSelectorMode.JSON_PATH },
            regex = profile.selector.takeIf { profile.selectorMode == TokenSelectorMode.REGEX }
        ), api, config, store
    )
}

internal fun executeAcquireSessionVar(
    input: AcquireSessionVar, api: MontoyaApi, config: McpConfig, store: VariableStore
): String = with(input) {
    require((request == null) != (historyId == null)) { "Supply exactly one of request or historyId." }
    require((jsonPath == null) != (regex == null)) { "Supply exactly one of jsonPath or regex." }
    require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid variable name." }
    require(identity.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) { "Invalid identity." }
    val selectedHost = host?.let(VariableStore::normalizeHost)
    if (jsonPath != null) {
        require(jsonPath.matches(Regex("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+])*"))) {
            "Invalid JSONPath."
        }
    } else {
        require(regex!!.length <= 1000 && runCatching { Regex(regex!!) }.isSuccess) { "Invalid extraction regex." }
    }

    val exchange: BoundResponse
    val sourceHost: String
    if (request != null) {
        val parsed = RequestTemplate.parse(request)
        sourceHost = parsed.host
        exchange = sendBound(api, config, store, request, identity, sourceHost)
    } else {
        require(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "HTTP history access denied by Burp Suite."
        }
        val item = findProxyHistoryItem(api, historyId!!) ?: throw IllegalArgumentException("History item unavailable.")
        val original = item.request() ?: throw IllegalArgumentException("History request unavailable.")
        val service = item.httpService()
        sourceHost = VariableStore.normalizeHost(service.host())
        require(!original.toString().contains("{{") && !original.toString().contains("}}")) {
            "History request contains unresolved placeholders."
        }
        require(runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                service.host(), service.port(), config, McpLogSanitizer.text(original.toString(), store), api
            )
        }) { "Send HTTP request denied by Burp Suite." }
        val started = System.nanoTime()
        val sent = try { api.http().sendRequest(original) } catch (_: Exception) {
            throw IllegalStateException("Token request failed.")
        } ?: throw IllegalStateException("No HTTP response.")
        exchange = BoundResponse(sent, (System.nanoTime() - started) / 1_000_000)
    }

    val response = exchange.response.response() ?: throw IllegalStateException("No HTTP response.")
    require(response.statusCode().toInt() in 200..299) { "Token request returned HTTP ${response.statusCode()}." }
    val body = response.bodyToString()
    val value = if (jsonPath != null) extractJsonPath(body, jsonPath) else extractRegex(response.toString(), regex!!)
    require(!value.isNullOrEmpty()) { "Token extraction found no value." }
    val boundHost = selectedHost ?: sourceHost
    val handle = store.put(name, value, identity, boundHost)
    store.rememberResponse(identity, sourceHost, body, response.toString())
    "Stored $handle for $identity at $boundHost from token request (HTTP ${response.statusCode()}, ${exchange.elapsedMs}ms)."
}

internal fun executeAuthzReplay(input: AuthzReplay, api: MontoyaApi, config: McpConfig, store: VariableStore): String = with(input) {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid replay ID." }
    require(roles.size in 2..8 && roles.distinct().size == roles.size &&
        roles.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) }) { "Provide two to eight distinct role names." }
    require((request == null) != (historyId == null)) { "Supply a request or historyId." }
    val template = request ?: run {
        require(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "HTTP history access denied by Burp Suite."
        }
        val item = findProxyHistoryItem(api, historyId!!) ?: throw IllegalArgumentException("History item unavailable.")
        item.request()?.toString() ?: throw IllegalArgumentException("History request unavailable.")
    }
    require(request == null || store.redact(request) == request) {
        "Use variable handles rather than literal stored secrets."
    }
    val parsed = RequestTemplate.parse(store.redact(template))
    val selectedHost = host?.let(VariableStore::normalizeHost) ?: parsed.host
    require(parsed.host == selectedHost) { "Request host mismatch." }
    require(expect == null || (expect.keys == roles.toSet() && expect.values.all { !it.status.isNullOrEmpty() ||
        it.bodyContains.isNotEmpty() || it.bodyNotContains.isNotEmpty() || it.jsonPath.isNotEmpty() })) {
        "Expect must contain an assertion for every role."
    }
    require(expect != null || ("admin" in roles && roles.any { it != "admin" })) {
        "Default assertions need admin and a lower-privilege role."
    }
    require(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.ORGANIZER, config) }) {
        "Organizer access denied by Burp Suite."
    }
    require(runBlocking {
        HttpRequestSecurity.checkHttpRequestPermission(
            parsed.service.host(), parsed.service.port(), config, store.redact(template), api
        )
    }) { "Send HTTP request denied by Burp Suite." }

    // Resolve every role before sending any request; a missing or cross-role value aborts the whole replay.
    val prepared = roles.associateWith { store.expand(store.redact(template), it, selectedHost) }
    val lines = mutableListOf<String>()
    val bodies = LinkedHashMap<String, String>()
    var allPassed = true
    for (role in roles) {
        val outgoing = prepared.getValue(role)
        val start = System.nanoTime()
        val exchange = api.http().sendRequest(outgoing) ?: throw IllegalStateException("No HTTP response.")
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val response = exchange.response() ?: throw IllegalStateException("No HTTP response.")
        val body = response.bodyToString()
        bodies[role] = body
        store.rememberResponse(role, selectedHost, body, response.toString())
        val rule = expect?.getValue(role) ?: if (role == "admin") AuthzExpect(status = (200..299).toList())
            else AuthzExpect(status = listOf(401, 403))
        val (passed, checks) = evaluateAuthz(rule, response.statusCode().toInt(), body)
        allPassed = allPassed && passed
        extract.forEach { (name, path) ->
            extractJsonPath(body, path)?.let { store.put(name, it, role, selectedHost) }
        }
        val verdict = if (passed) "PASS" else "FAIL"
        lines += "$role | ${response.statusCode()} | ${elapsed}ms | $verdict | ${checks.joinToString(",")}"
        val note = store.redact("[authz:$id] role=$role status=${response.statusCode()} verdict=$verdict checks=${checks.joinToString(",")}")
        val evidenceRequest = HttpRequest.httpRequest(parsed.service, store.redact(parsed.raw))
        val evidenceResponse = HttpResponse.httpResponse(
            "HTTP/1.1 ${response.statusCode()} Authz Replay\r\nContent-Type: text/plain\r\n\r\n$verdict"
        )
        val evidence = HttpRequestResponse.httpRequestResponse(
            evidenceRequest, evidenceResponse, Annotations.annotations(note)
        )
        api.organizer().sendToOrganizer(evidence)
    }
    val verdict = if (allPassed) "PASS" else "FAIL"
    val baselineRole = if ("admin" in roles) "admin" else roles.first()
    val baseline = store.redact(bodies.getValue(baselineRole))
    val differences = roles.filter { it != baselineRole }.joinToString("\n") { role ->
        val compared = store.redact(bodies.getValue(role))
        val sharedLength = minOf(compared.length, baseline.length)
        val firstDifference = (0 until sharedLength).firstOrNull { compared[it] != baseline[it] }
            ?: if (compared.length == baseline.length) -1 else sharedLength
        "$role vs $baselineRole | body=${if (firstDifference < 0) "same" else "different"} | " +
            "firstDifference=$firstDifference | lengthDelta=${compared.length - baseline.length}"
    }
    "authz:$id $verdict\nrole | status | timing | verdict | checks\n${lines.joinToString("\n")}" +
        "\nResponse diff (redacted):\n$differences"
}

/** Raw requests need an explicit transport on nonstandard ports; curl and absolute URLs carry a scheme. */
internal fun resolveRawTransport(template: String, usesHttps: Boolean?): Boolean? {
    if (template.trimStart().startsWith("curl ")) {
        require(usesHttps == null) { "Curl URL already selects HTTP or HTTPS; omit usesHttps." }
        return null
    }
    val target = template.lineSequence().firstOrNull().orEmpty().trim()
        .substringAfter(' ', "").substringBefore(' ', "")
    if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
        require(usesHttps == null) { "Absolute request URL already selects HTTP or HTTPS; omit usesHttps." }
        return null
    }
    if (usesHttps != null) return usesHttps
    val port = RequestTemplate.parse(template).service.port()
    return when (port) {
        80 -> false
        443 -> true
        else -> throw IllegalArgumentException(
            "Raw HTTP on a nonstandard port needs usesHttps=false for HTTP or true for HTTPS; " +
                "alternatively use curl with an explicit URL scheme."
        )
    }
}

/** Do not make clients repeat a role name that redaction may have rendered as a handle. */
internal fun resolveSendIdentity(
    store: VariableStore, rawTemplate: String, host: String, requested: String?
): String {
    if (requested != null) {
        require(requested.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
            "Identity must be a role name, not a redacted display handle. Omit it for the only ready role."
        }
        return requested
    }
    val required = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}")
        .findAll(rawTemplate).map { it.groupValues[1] }.toSet()
    require(required.isNotEmpty()) { "Supply an identity or at least one variable handle." }
    val ready = store.listNames().filter { it.host == host }.groupBy { it.identity }
        .filter { (_, names) -> required.all { name -> names.any { it.name == name } } }.keys
    require(ready.size == 1) {
        if (ready.isEmpty()) "No role has all request handles for this host."
        else "Multiple roles have all request handles; specify an identity."
    }
    return ready.single()
}

internal fun sendBound(
    api: MontoyaApi, config: McpConfig, store: VariableStore,
    template: String, identity: String, host: String, rawUsesHttps: Boolean? = null
): BoundResponse {
    val parsed = RequestTemplate.parse(template, rawUsesHttps)
    require(parsed.host == VariableStore.normalizeHost(host)) { "Request host mismatch." }
    require(store.redact(template) == template) { "Use variable handles rather than literal stored secrets." }
    require(runBlocking {
        HttpRequestSecurity.checkHttpRequestPermission(
            parsed.service.host(), parsed.service.port(), config, store.redact(template), api
        )
    }) { "Send HTTP request denied by Burp Suite." }
    val outgoing = store.expand(template, identity, host, rawUsesHttps)
    val start = System.nanoTime()
    val response = api.http().sendRequest(outgoing) ?: throw IllegalStateException("No HTTP response.")
    return BoundResponse(response, (System.nanoTime() - start) / 1_000_000)
}

internal fun evaluateAuthz(expect: AuthzExpect, status: Int, body: String): Pair<Boolean, List<String>> {
    val checks = mutableListOf<String>()
    if (expect.status != null) checks += if (status in expect.status) "status:pass" else "status:fail"
    expect.bodyContains.forEach { checks += if (body.contains(it)) "bodyContains:pass" else "bodyContains:fail" }
    expect.bodyNotContains.forEach { checks += if (!body.contains(it)) "bodyNotContains:pass" else "bodyNotContains:fail" }
    expect.jsonPath.forEach { (path, wanted) ->
        checks += if (extractJsonPath(body, path) == wanted) "jsonPath:pass" else "jsonPath:fail"
    }
    return (checks.isNotEmpty() && checks.none { it.endsWith(":fail") }) to checks
}

internal fun extractJsonPath(body: String, path: String): String? {
    if (body.length > 1_000_000 || !path.matches(Regex("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+])*"))) return null
    var current: JsonElement = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
    Regex("\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[([0-9]+)]").findAll(path.drop(1)).forEach { match ->
        current = if (match.groupValues[1].isNotEmpty()) {
            (current as? JsonObject)?.get(match.groupValues[1]) ?: return null
        } else {
            (current as? JsonArray)?.getOrNull(match.groupValues[2].toInt()) ?: return null
        }
    }
    return (current as? JsonPrimitive)?.content
}

internal fun extractRegex(body: String, pattern: String): String? {
    require(body.length <= 1_000_000 && pattern.length <= 1000) { "Extraction input too large." }
    val match = Regex(pattern).find(body) ?: return null
    return match.groups[1]?.value ?: match.value
}
