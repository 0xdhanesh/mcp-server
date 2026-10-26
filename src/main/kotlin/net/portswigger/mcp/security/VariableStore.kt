package net.portswigger.mcp.security

import burp.api.montoya.http.message.requests.HttpRequest
import net.portswigger.mcp.tools.normalizeHttpContent
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Project-scoped, memory-only values. A holder has no value getter or serializer. */
class VariableStore {
    data class Name(val name: String, val identity: String, val host: String)
    private data class Key(val name: String, val identity: String, val host: String)

    private class Holder private constructor(@Transient private val chars: CharArray, private val name: String) {
        override fun toString(): String = "{{$name}}"

        fun appendAtSend(builder: StringBuilder) { builder.append(chars) }

        fun redactionForms(): Set<String> {
            val raw = String(chars)
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8)
            val lowerPercent = Regex("%[0-9A-Fa-f]{2}").replace(encoded) { it.value.lowercase() }
            return setOf(
                raw, Base64.getEncoder().encodeToString(bytes),
                Base64.getEncoder().withoutPadding().encodeToString(bytes),
                Base64.getUrlEncoder().encodeToString(bytes),
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                encoded, encoded.replace("+", "%20"), lowerPercent,
                lowerPercent.replace("+", "%20")
            ).filter { it.isNotEmpty() }.toSet()
        }

        fun erase() { chars.fill('\u0000') }

        companion object {
            fun of(name: String, value: String) = Holder(value.toCharArray(), name)
        }
    }

    private val values = LinkedHashMap<Key, Holder>()
    internal class ResponseSnapshot(val body: String, val raw: String) {
        override fun toString(): String = "<session response>"
    }
    private val lastResponses = LinkedHashMap<Pair<String, String>, ResponseSnapshot>()
    private val placeholder = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}")
    private var clearEpoch = 0L
    private var closed = false
    @Volatile internal var onValueStored: (() -> Unit)? = null

    @Synchronized
    internal fun clearEpoch(): Long = clearEpoch

    @Synchronized
    fun close() {
        clear()
        closed = true
    }

    fun put(name: String, value: String, identity: String, host: String): String {
        synchronized(this) {
            require(!closed) { "Session variables are unavailable." }
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid variable name." }
            require(identity.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) { "Invalid identity." }
            require(value.isNotEmpty()) { "Variable value is empty." }
            require(!placeholder.containsMatchIn(value)) {
                "Enter the actual variable value; {{name}} is a handle, not a value."
            }
            val key = Key(name, identity, normalizeHost(host))
            values.put(key, Holder.of(name, value))?.erase()
        }
        onValueStored?.invoke()
        return "{{$name}}"
    }

    @Synchronized
    fun getHandle(name: String): String {
        require(!closed) { "Session variables are unavailable." }
        require(values.keys.any { it.name == name }) { "Unknown variable." }
        return "{{$name}}"
    }

    @Synchronized
    fun listNames(): List<Name> = values.keys.map { Name(it.name, it.identity, it.host) }

    @Synchronized
    fun clear(identity: String? = null) {
        clearEpoch++
        val keys = values.keys.filter { identity == null || it.identity == identity }
        keys.forEach { values.remove(it)?.erase() }
        if (identity == null) lastResponses.clear()
        else lastResponses.keys.removeIf { it.first == identity }
    }

    @Synchronized
    internal fun rememberResponse(identity: String, host: String, body: String, raw: String) {
        require(!closed) { "Session variables are unavailable." }
        lastResponses[identity to normalizeHost(host)] = ResponseSnapshot(body, raw)
    }

    @Synchronized
    internal fun lastResponse(identity: String, host: String): ResponseSnapshot? =
        lastResponses[identity to normalizeHost(host)]

    /** This is the only operation that materializes a stored value into an HTTP request. */
    @Synchronized
    fun expand(template: String, identity: String, host: String, rawUsesHttps: Boolean? = null): HttpRequest {
        require(!closed) { "Session variables are unavailable." }
        val parsed = RequestTemplate.parse(template, rawUsesHttps)
        parsed.requirePlaceholderCredentials()
        val boundHost = normalizeHost(host)
        require(parsed.host == boundHost) { "Request host does not match the selected host." }
        require(!template.contains(Regex("\\{\\{[^}]*}(?!})"))) { "Malformed placeholder." }
        val expanded = StringBuilder()
        var cursor = 0
        placeholder.findAll(parsed.raw).forEach { match ->
            expanded.append(parsed.raw, cursor, match.range.first)
            val name = match.groupValues[1]
            val holder = values[Key(name, identity, boundHost)]
                ?: throw IllegalArgumentException("Unknown variable or identity/host mismatch: {{$name}}.")
            holder.appendAtSend(expanded)
            cursor = match.range.last + 1
        }
        expanded.append(parsed.raw, cursor, parsed.raw.length)
        require(!expanded.contains("{{") && !expanded.contains("}}")) { "Unresolved placeholder." }
        return HttpRequest.httpRequest(parsed.service, correctContentLength(normalizeHttpContent(expanded.toString())))
    }

    /** Attach a role-bound bearer value only while preparing an HTTP send. */
    @Synchronized
    internal fun withBearerToken(request: HttpRequest, name: String, identity: String, host: String): HttpRequest {
        require(!closed) { "Session variables are unavailable." }
        val boundHost = normalizeHost(host)
        require(normalizeHost(request.httpService().host()) == boundHost) { "Request host mismatch." }
        val holder = values[Key(name, identity, boundHost)]
            ?: throw IllegalArgumentException("Unknown variable or identity/host mismatch: {{$name}}.")
        val authorization = StringBuilder("Bearer ")
        holder.appendAtSend(authorization)
        return request.withRemovedHeader("Authorization").withAddedHeader("Authorization", authorization.toString())
    }

    @Synchronized
    fun redact(text: String): String {
        if (closed) return "<session variables unavailable>"
        if (text.isEmpty() || values.isEmpty()) return text
        var result = text
        val replacements = values.entries.flatMap { (key, holder) ->
            holder.redactionForms().map { form -> form to "{{${key.name}}}" }
        }.distinctBy { it.first }.sortedByDescending { it.first.length }
        replacements.forEach { (form, handle) -> result = result.replace(form, handle) }
        return result
    }

    companion object {
        fun normalizeHost(host: String): String {
            val normalized = host.trim().lowercase()
            require(normalized.matches(Regex("[a-z0-9.-]+")) && !normalized.startsWith('.') &&
                !normalized.endsWith('.')) { "Invalid host." }
            return normalized
        }
    }
}

private fun correctContentLength(raw: String): String {
    val separator = raw.indexOf("\r\n\r\n")
    if (separator < 0) return raw
    val header = raw.substring(0, separator)
    val body = raw.substring(separator + 4)
    val lines = header.split("\r\n")
    val hadLength = lines.drop(1).any { it.substringBefore(':').trim().equals("Content-Length", true) }
    val withoutLength = lines.filterIndexed { index, line ->
        index == 0 || !line.substringBefore(':').trim().equals("Content-Length", true)
    }
    return buildString {
        append(withoutLength.joinToString("\r\n"))
        if (body.isNotEmpty() || hadLength) {
            append("\r\nContent-Length: ")
            append(body.toByteArray(StandardCharsets.UTF_8).size)
        }
        append("\r\n\r\n")
        append(body)
    }
}
