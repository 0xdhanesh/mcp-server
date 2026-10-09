package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tool calls made by an MCP client. A failed call keeps the text Burp MCP sent
 * back to the model, which is either the "Error:" result or one of the
 * failure strings the tools already return.
 */
internal object ToolActivity {
    const val MAX_CALLS = 200

    data class Call(
        val at: LocalTime,
        val toolName: String,
        val passed: Boolean,
        val failureMessage: String?
    ) {
        fun line(): String = formatToolActivityLine(at, passed, toolName)
    }

    private val calls = ArrayDeque<Call>()
    private val listeners = CopyOnWriteArrayList<(List<Call>) -> Unit>()

    fun record(toolName: String, result: CallToolResult) {
        val message = llmTrace(result.content)
        val failed = toolCallFailed(result.isError == true, message)
        val call = Call(
            at = LocalTime.now(),
            toolName = toolName,
            passed = !failed,
            failureMessage = if (failed) message else null
        )
        val snapshot = synchronized(calls) {
            calls.addFirst(call)
            while (calls.size > MAX_CALLS) {
                calls.removeLast()
            }
            calls.toList()
        }
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    fun snapshot(): List<Call> = synchronized(calls) { calls.toList() }

    fun addListener(listener: (List<Call>) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (List<Call>) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        synchronized(calls) { calls.clear() }
    }
}

internal fun formatToolActivityLine(at: LocalTime, passed: Boolean, toolName: String): String {
    val status = if (passed) "Pass" else "Fail"
    return "${at.format(ACTIVITY_TIME)} - $status - $toolName"
}

@PublishedApi
internal fun toolResult(toolName: String, block: () -> CallToolResult): CallToolResult {
    val result = try {
        block()
    } catch (e: Exception) {
        CallToolResult(
            content = listOf(TextContent("Error: ${e.message}")),
            isError = true
        )
    }
    ToolActivity.record(toolName, result)
    return result
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
