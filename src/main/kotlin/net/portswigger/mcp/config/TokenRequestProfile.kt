package net.portswigger.mcp.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.security.RequestTemplate
import net.portswigger.mcp.security.VariableStore

@Serializable
enum class TokenRequestMode { TEMPLATE, HTTP_FIELDS }

@Serializable
enum class RawHttpScheme { HTTPS, HTTP }

@Serializable
enum class TokenSelectorMode { JSON_PATH, REGEX }

/** A persisted request template. Credential values must be handles supplied from the memory-only store. */
@Serializable
data class TokenRequestProfile(
    val mode: TokenRequestMode = TokenRequestMode.TEMPLATE,
    val template: String = "",
    val rawHttpScheme: RawHttpScheme = RawHttpScheme.HTTPS,
    val method: String = "POST",
    val url: String = "",
    val headers: String = "",
    val body: String = "",
    val variableName: String = "session",
    val targetHost: String = "",
    val defaultIdentity: String = "",
    val selectorMode: TokenSelectorMode = TokenSelectorMode.JSON_PATH,
    val selector: String = "$.access_token"
) {
    fun requestTemplate(): String = when (mode) {
        TokenRequestMode.TEMPLATE -> if (rawHttpScheme == RawHttpScheme.HTTP &&
            !template.trimStart().startsWith("curl ")) {
            val first = template.lineSequence().firstOrNull()?.trimEnd('\r').orEmpty()
            val parts = first.split(' ')
            val host = template.lineSequence().firstOrNull { it.startsWith("Host:", true) }
                ?.substringAfter(':')?.trim()
            if (parts.size == 3 && parts[1].startsWith('/') && !host.isNullOrEmpty()) {
                template.replaceRange(0, first.length, "${parts[0]} http://$host${parts[1]} ${parts[2]}")
            } else template
        } else template
        TokenRequestMode.HTTP_FIELDS -> {
            val methodName = method.trim().uppercase()
            require(methodName.matches(Regex("[A-Z]+"))) { "Invalid HTTP method." }
            val requestUrl = url.trim()
            require(requestUrl.startsWith("https://") || requestUrl.startsWith("http://")) {
                "Request URL must use HTTP or HTTPS."
            }
            val headerLines = headers.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            buildString {
                append("curl -s -X ").append(methodName).append(" --url ").append(curlQuote(requestUrl))
                headerLines.forEach { append(" -H ").append(curlQuote(it)) }
                if (body.isNotEmpty() && !HANDLE.matches(body.trim()) &&
                    headerLines.none { it.startsWith("Content-Type:", ignoreCase = true) }) {
                    val type = if (body.trimStart().startsWith('{') || body.trimStart().startsWith('[')) {
                        "application/json"
                    } else {
                        "application/x-www-form-urlencoded"
                    }
                    append(" -H ").append(curlQuote("Content-Type: $type"))
                }
                if (body.isNotEmpty()) append(" --data-raw ").append(curlQuote(body))
            }
        }
    }

    fun validateForPersistence(store: VariableStore) {
        when (mode) {
            TokenRequestMode.TEMPLATE -> require(url.isBlank() && headers.isBlank() && body.isBlank()) {
                "Inactive HTTP fields must be empty."
            }
            TokenRequestMode.HTTP_FIELDS -> require(template.isBlank()) { "Inactive request template must be empty." }
        }
        require(variableName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid variable name." }
        require(defaultIdentity.isBlank() || defaultIdentity.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
            "Invalid default role."
        }
        if (targetHost.isNotBlank()) VariableStore.normalizeHost(targetHost)
        require(selector.length in 1..1000) { "Enter a token extraction rule." }
        when (selectorMode) {
            TokenSelectorMode.JSON_PATH -> require(selector.matches(Regex("\\$(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]+])*"))) {
                "Invalid JSONPath."
            }
            TokenSelectorMode.REGEX -> {
                require(!selector.startsWith("$.")) { "Choose JSONPath to extract $.access_token." }
                require(runCatching { Regex(selector) }.isSuccess) { "Invalid extraction regex." }
            }
        }
        val request = requestTemplate()
        require(request.length in 1..32768) { "Request template must be between 1 and 32768 characters." }
        require(store.redact(request) == request) { "Replace stored values with variable handles before saving." }
        val parsed = RequestTemplate.parse(request)
        parsed.requirePlaceholderCredentials()
        requireSafeTemplate(parsed.raw)
    }
}

/** Readiness contains names and roles only; no stored value is inspected or returned. */
data class TokenRequestReadiness(
    val requestHost: String,
    val tokenUseHost: String,
    val requiredNames: Set<String>,
    val readyRoles: List<String>,
    val defaultRole: String,
    val missingForDefault: Set<String>
) {
    fun selectedRole(explicitRole: String? = null): String? = when {
        explicitRole != null -> explicitRole
        defaultRole.isNotBlank() && missingForDefault.isEmpty() -> defaultRole
        readyRoles.size == 1 -> readyRoles.single()
        else -> null
    }

    fun missingFor(role: String, store: VariableStore): Set<String> {
        val present = store.listNames().filter { it.identity == role && it.host == requestHost }.map { it.name }.toSet()
        return requiredNames - present
    }

    fun summary(): String = when {
        defaultRole.isNotBlank() && missingForDefault.isEmpty() -> "Ready for default role $defaultRole."
        defaultRole.isNotBlank() && readyRoles.size == 1 ->
            "Default role $defaultRole lacks ${missingForDefault.joinToString(", ") { "{{$it}}" }}; " +
                "ready for only configured role ${readyRoles.single()}."
        defaultRole.isNotBlank() -> "Missing ${missingForDefault.joinToString(", ") { "{{$it}}" }} for default role $defaultRole."
        readyRoles.size == 1 -> "Ready for only configured role ${readyRoles.single()}; no default role needed."
        readyRoles.size > 1 -> "Multiple roles are ready (${readyRoles.joinToString(", ")}); set a default role."
        else -> "No ready role; store the token request inputs in memory or set a default role."
    }
}

fun TokenRequestProfile.readiness(store: VariableStore): TokenRequestReadiness {
    val parsed = RequestTemplate.parse(requestTemplate())
    val requestHost = parsed.host
    val tokenUseHost = targetHost.ifBlank { requestHost }.let(VariableStore::normalizeHost)
    val required = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}")
        .findAll(parsed.raw).map { it.groupValues[1] }.toSet()
    val byRole = store.listNames().filter { it.host == requestHost }.groupBy { it.identity }
    val ready = if (required.isEmpty()) emptyList() else byRole.filter { (_, names) ->
        required.all { needed -> names.any { it.name == needed } }
    }.keys.sorted()
    val missing = if (defaultIdentity.isBlank()) emptySet() else
        required - byRole[defaultIdentity].orEmpty().map { it.name }.toSet()
    return TokenRequestReadiness(requestHost, tokenUseHost, required, ready, defaultIdentity, missing)
}

private fun curlQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private val HANDLE = Regex("\\{\\{[A-Za-z_][A-Za-z0-9_]*}}")
private val SAFE_GRANT_TYPES = setOf("password", "client_credentials", "authorization_code", "refresh_token")
private val SAFE_HEADERS = setOf("host", "content-length", "content-type", "accept", "user-agent", "accept-language", "accept-encoding")
private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "x-api-key")

private fun requireSafeTemplate(raw: String) {
    val normalized = raw.replace("\r\n", "\n")
    val separator = normalized.indexOf("\n\n")
    val header = if (separator >= 0) normalized.substring(0, separator) else normalized
    val body = if (separator >= 0) normalized.substring(separator + 2) else ""
    val path = header.lineSequence().first().split(' ').getOrNull(1).orEmpty()
    val query = path.substringAfter('?', "")
    if (query.isNotEmpty()) {
        require(query.split('&').all { part ->
            val name = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) &&
                (HANDLE.matches(value) || name == "grant_type" && value in SAFE_GRANT_TYPES)
        }) { "Query parameter values must use variable handles before saving." }
    }
    header.lineSequence().drop(1).filter(String::isNotBlank).forEach { line ->
        val name = line.substringBefore(':').trim().lowercase()
        val value = line.substringAfter(':', "").trim()
        require(name in SAFE_HEADERS || name in CREDENTIAL_HEADERS || HANDLE.matches(value) ||
            value.matches(Regex("(?i)(?:Bearer|Basic) \\{\\{[A-Za-z_][A-Za-z0-9_]*}}"))) {
            "Custom header values must use variable handles before saving."
        }
    }
    if (body.isBlank()) return
    if (HANDLE.matches(body.trim())) return
    val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
    val jsonContentType = header.lineSequence().drop(1).firstOrNull { it.startsWith("Content-Type:", true) }
        ?.substringAfter(':')?.substringBefore(';')?.trim()
    require(jsonContentType == null || !jsonContentType.equals("application/json", true) || json != null) {
        "Invalid JSON body. Quote each {{name}} handle as a JSON string."
    }
    if (json != null) {
        requireSafeJson(json)
        return
    }
    require(body.split('&').all { part ->
        val name = part.substringBefore('=', "").trim()
        val value = part.substringAfter('=', "").trim()
        name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) &&
            (HANDLE.matches(value) || name == "grant_type" && value in SAFE_GRANT_TYPES)
    }) { "Body parameter values must use variable handles before saving." }
}

private fun requireSafeJson(element: JsonElement, key: String = "") {
    when (element) {
        is JsonObject -> element.forEach { (name, value) -> requireSafeJson(value, name) }
        is JsonArray -> element.forEach { requireSafeJson(it, key) }
        is JsonPrimitive -> require(element.isString &&
            (HANDLE.matches(element.content) || key == "grant_type" && element.content in SAFE_GRANT_TYPES)) {
            "JSON values must use variable handles before saving."
        }
    }
}
