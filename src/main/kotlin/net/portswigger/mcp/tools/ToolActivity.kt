package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.security.VariableStore
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/** Bounded, in-memory MCP tool call log. Input and output are sanitized before retention. */
internal object ToolActivity {
    const val MAX_CALLS = 200

    data class Call(
        val at: LocalTime,
        val timestamp: Instant,
        val toolName: String,
        val passed: Boolean,
        val failureMessage: String?,
        val input: String,
        val output: String
    ) {
        fun line(): String = formatToolActivityLine(at, passed, toolName)
    }

    private val calls = ArrayDeque<Call>()
    private val listeners = CopyOnWriteArrayList<(List<Call>) -> Unit>()

    fun record(toolName: String, result: CallToolResult, input: JsonElement? = null) {
        val store = ToolResultRedaction.store
        val message = llmTrace(result.content)
        val failed = toolCallFailed(result.isError == true, message)
        val call = Call(
            at = LocalTime.now(),
            timestamp = Instant.now(),
            toolName = toolName,
            passed = !failed,
            failureMessage = if (failed) McpLogSanitizer.text(message, store) else null,
            input = McpLogSanitizer.input(toolName, input, store),
            output = McpLogSanitizer.output(result, store)
        )
        val snapshot = synchronized(calls) {
            val currentStore = ToolResultRedaction.store
            calls.addFirst(call.copy(
                failureMessage = call.failureMessage?.let { McpLogSanitizer.text(it, currentStore) },
                input = McpLogSanitizer.jsonText(call.input, currentStore),
                output = McpLogSanitizer.jsonText(call.output, currentStore)
            ))
            while (calls.size > MAX_CALLS) {
                calls.removeLast()
            }
            calls.toList()
        }
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    fun snapshot(): List<Call> = synchronized(calls) {
        val store = ToolResultRedaction.store
        calls.map { call ->
            call.copy(
                failureMessage = call.failureMessage?.let { McpLogSanitizer.text(it, store) },
                input = McpLogSanitizer.jsonText(call.input, store),
                output = McpLogSanitizer.jsonText(call.output, store)
            )
        }
    }

    fun scrubWith(store: VariableStore) {
        val snapshot = synchronized(calls) {
            val sanitized = calls.map { call ->
                call.copy(
                    failureMessage = call.failureMessage?.let { McpLogSanitizer.text(it, store) },
                    input = McpLogSanitizer.jsonText(call.input, store),
                    output = McpLogSanitizer.jsonText(call.output, store)
                )
            }
            calls.clear()
            calls.addAll(sanitized)
            calls.toList()
        }
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    fun exportJsonl(): String {
        val store = ToolResultRedaction.store
        val entries = snapshot().asReversed()
        return entries.joinToString("\n", postfix = if (entries.isEmpty()) "" else "\n") { call ->
            EXPORT_JSON.encodeToString(
                ExportedCall(
                    timestamp = call.timestamp.toString(), tool = call.toolName, passed = call.passed,
                    input = McpLogSanitizer.jsonText(call.input, store),
                    output = McpLogSanitizer.jsonText(call.output, store)
                )
            )
        }
    }

    fun addListener(listener: (List<Call>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<Call>) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        synchronized(calls) { calls.clear() }
        listeners.forEach { listener -> runCatching { listener(emptyList()) } }
    }
}

@Serializable
private data class ExportedCall(
    val timestamp: String,
    val tool: String,
    val passed: Boolean,
    val input: String,
    val output: String
)

private val ACTIVITY_JSON = Json { prettyPrint = true }
private val EXPORT_JSON = Json

internal object McpLogSanitizer {
    private val sensitiveKeys = setOf(
        "password", "passwd", "secret", "secretkey", "clientsecret", "token", "accesstoken",
        "refreshtoken", "authorization", "cookie", "apikey"
    )
    private val credentialHeader = Regex("(?im)^(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key):[^\\r\\n]*")
    private val safeHeaderValue = Regex("(?i)\\s*(?:(?:Bearer|Basic)\\s+)?\\{\\{[A-Za-z_][A-Za-z0-9_]*}}\\s*")
    private val bearer = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]{8,}")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")
    private val inlineSecret = Regex("(?i)\\b(password|secret|token|api_key|session|jwt)=(?!\\{\\{)([^&\\s]+)")
    private val jsonSecret = Regex("(?i)(\"(?:password|passwd|secret|client_secret|refresh_token|access_token|token|api_key)\"\\s*:\\s*\")([^\"]*)(\")")

    fun input(tool: String, arguments: JsonElement?, store: VariableStore?): String {
        val root = arguments ?: JsonObject(emptyMap())
        val handle = (root as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }?.let { "{{$it}}" }
        return ACTIVITY_JSON.encodeToString(JsonElement.serializer(), sanitize(root, store, tool == "set_session_var", handle))
    }

    fun output(result: CallToolResult, store: VariableStore?): String {
        val json = runCatching { ACTIVITY_JSON.encodeToString(result) }.getOrElse { "{\"content\":\"<unavailable>\"}" }
        return jsonText(json, store)
    }

    fun jsonText(json: String, store: VariableStore?): String {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return text(json, store)
        return ACTIVITY_JSON.encodeToString(JsonElement.serializer(), sanitize(parsed, store, false, null))
    }

    fun text(value: String, store: VariableStore?): String {
        var safe = store?.redact(value) ?: value
        safe = credentialHeader.replace(safe) {
            val headerValue = it.value.substringAfter(':')
            if (safeHeaderValue.matches(headerValue)) it.value else "${it.groupValues[1]}: <redacted>"
        }
        safe = bearer.replace(safe) { "${it.groupValues[1]} <redacted>" }
        safe = jwt.replace(safe, "<redacted JWT>")
        safe = inlineSecret.replace(safe) { "${it.groupValues[1]}=<redacted>" }
        safe = jsonSecret.replace(safe) { "${it.groupValues[1]}<redacted>${it.groupValues[3]}" }
        return safe
    }

    private fun sanitize(element: JsonElement, store: VariableStore?, sessionValue: Boolean, handle: String?): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                val normalized = key.lowercase().filter { it.isLetterOrDigit() }
                when {
                    sessionValue && key == "value" -> JsonPrimitive(handle ?: "<redacted>")
                    normalized in sensitiveKeys -> JsonPrimitive("<redacted>")
                    else -> sanitize(value, store, false, null)
                }
            })
            is JsonArray -> JsonArray(element.map { sanitize(it, store, false, null) })
            is JsonPrimitive -> if (element.isString) JsonPrimitive(text(element.content, store)) else element
        }
}

/** One result boundary for every registered MCP tool, before activity recording or network return. */
internal object ToolResultRedaction {
    @Volatile var store: VariableStore? = null

    fun apply(result: CallToolResult, active: VariableStore? = store, siteMapResult: Boolean = false): CallToolResult {
        active ?: return result
        return CallToolResult(
            content = result.content.map { block ->
                TextContent(if (block is TextContent) {
                    if (siteMapResult && result.isError != true) redactSiteMapJson(block.text.orEmpty(), active)
                    else active.redact(block.text.orEmpty())
                } else "<non-text content withheld>")
            },
            isError = result.isError
        )
    }

    private fun redactSiteMapJson(text: String, active: VariableStore): String {
        // Preserve schema keys and opaque generated handles: short stored values can otherwise
        // corrupt JSON keys or a handle and make get_sitemap_item impossible to call.
        fun redact(node: JsonElement): JsonElement = when (node) {
            is JsonObject -> JsonObject(node.mapValues { (key, value) ->
                if (key == "id" && value is JsonPrimitive && value.isString &&
                    value.content.matches(Regex("sm-[0-9a-f]{64}"))) value else redact(value)
            })
            is JsonArray -> JsonArray(node.map(::redact))
            is JsonPrimitive -> if (node.isString) JsonPrimitive(active.redact(node.content)) else node
        }
        return Json.encodeToString(JsonElement.serializer(), redact(Json.parseToJsonElement(text)))
    }
}

internal fun safeMetadata(value: String): String = ToolResultRedaction.store?.redact(value) ?: value

internal fun requireNoStoredSecret(value: String) {
    require(safeMetadata(value) == value) { "Use a variable handle; this value contains a stored secret." }
}

internal fun formatToolActivityLine(at: LocalTime, passed: Boolean, toolName: String): String {
    val status = if (passed) "Pass" else "Fail"
    return "${at.format(ACTIVITY_TIME)} - $status - $toolName"
}

@PublishedApi
internal fun toolResult(toolName: String, input: JsonElement? = null, block: () -> CallToolResult): CallToolResult {
    val active = ToolResultRedaction.store
    val startEpoch = active?.clearEpoch()
    var scopeDenied = false
    val result = try {
        block()
    } catch (e: Exception) {
        scopeDenied = e is net.portswigger.mcp.security.OutOfScopeException
        CallToolResult(
            content = listOf(TextContent(if (scopeDenied)
                "{\"code\":\"OUT_OF_SCOPE\"}" else "Error: ${e.message}")),
            isError = true
        )
    }
    val redacted = if (active != null && toolName != "clear_session_vars" && active.clearEpoch() != startEpoch) {
        CallToolResult(content = listOf(TextContent("Session variables changed during this tool call.")), isError = true)
    } else {
        // Fixed protocol codes contain no request data and must survive short-value redaction.
        if (scopeDenied) result else ToolResultRedaction.apply(result, active, toolName == "list_sitemap" || toolName == "get_sitemap_item")
    }
    val safeInput = if (active != null && toolName != "clear_session_vars" && active.clearEpoch() != startEpoch) null else input
    ToolActivity.record(toolName, redacted, safeInput)
    return redacted
}

internal fun toolCallFailed(isError: Boolean, message: String): Boolean {
    return isError || isFailureMessage(message)
}

internal fun llmTrace(content: List<ContentBlock>?): String {
    if (content.isNullOrEmpty()) return ""
    return content.joinToString("\n") { block ->
        if (block is TextContent) block.text.orEmpty() else block.toString()
    }
}

/**
 * True when [message] is the text a tool returns to the model instead of a
 * successful result. Successful responses, including HTTP bodies, do not use
 * these openings.
 */
internal fun isFailureMessage(message: String): Boolean {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return false
    return FAILURE_PREFIXES.any { trimmed.startsWith(it) }
}

private val ACTIVITY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private val FAILURE_PREFIXES = listOf(
    "Error:",
    "Send HTTP request denied",
    "No Repeater tab named ",
    "Repeater Send was not available",
    "Burp's window is not available, so Repeater Send could not be used.",
    "Send button ran in Repeater tab",
    "Send shortcut ran in Repeater tab",
    "HTTP history access denied",
    "Logger access denied",
    "Logger table unavailable",
    "Logger comment not updated:",
    "Organizer access denied",
    "WebSocket history access denied",
    "Extension probe denied",
    "Extension command denied",
    "No proxy history item",
    "Proxy history item ",
    "No extension answered",
    "Provide historyId or regex",
    "Refusing to annotate",
    "Matched history items have no notes field.",
    "Saved Repeater notes for ",
    "User has disabled configuration editing",
    "<No active editor>",
    "<Current editor is not editable>"
)
