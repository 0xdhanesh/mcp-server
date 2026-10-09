package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class ToolActivityTest {
    @AfterEach
    fun clearActivity() {
        ToolActivity.clear()
    }

    @Test
    fun `line is time, pass or fail, and tool name`() {
        val at = LocalTime.of(9, 15, 2)
        assertEquals("09:15:02 - Pass - send_http1_request", formatToolActivityLine(at, true, "send_http1_request"))
        assertEquals("09:15:03 - Fail - send_repeater_request", formatToolActivityLine(at.plusSeconds(1), false, "send_repeater_request"))
    }

    @Test
    fun `failure keeps the message sent to the model`() {
        val trace = "Repeater Send was not available for tab 'login'. tabs=[]"
        val result = CallToolResult(content = listOf(TextContent(trace)), isError = false)

        assertTrue(toolCallFailed(result.isError == true, trace))
        assertEquals(trace, llmTrace(result.content))

        ToolActivity.record("send_repeater_request", result)
        val call = ToolActivity.snapshot().single()
        assertFalse(call.passed)
        assertEquals(trace, call.failureMessage)
        assertTrue(call.line().endsWith(" - Fail - send_repeater_request"))
    }

    @Test
    fun `exception text is a failure and a normal response is not`() {
        assertTrue(isFailureMessage("Error: boom"))
        assertTrue(isFailureMessage("Send HTTP request denied by Burp Suite"))
        assertTrue(isFailureMessage("No Repeater tab named 'login'."))
        assertTrue(isFailureMessage("HTTP history access denied by Burp Suite"))
        assertTrue(isFailureMessage("No extension answered X-ATOR-Command: status."))
        assertTrue(isFailureMessage("Burp's window is not available, so Repeater Send could not be used."))
        assertTrue(isFailureMessage("Send button ran in Repeater tab 'login', but no response text was visible yet."))
        assertTrue(isFailureMessage("Saved Repeater notes for 'login' in this session. Burp's Notes panel for that tab could not be updated."))
        assertTrue(isFailureMessage("User has disabled configuration editing. They can enable it in the MCP v1.2 tab in Burp"))
        assertTrue(isFailureMessage("<No active editor>"))

        assertFalse(isFailureMessage("HTTP/1.1 500 Internal Server Error\r\n\r\nError: from the server"))
        assertFalse(isFailureMessage("Opened Repeater tab 'login'."))
        assertFalse(isFailureMessage("Updated Repeater notes on 'login'."))
        assertFalse(isFailureMessage("No interactions detected"))
        assertFalse(isFailureMessage("No Repeater tabs have been opened by MCP in this session."))
    }

    @Test
    fun `records newest first and drops the oldest`() {
        repeat(ToolActivity.MAX_CALLS + 5) { index ->
            ToolActivity.record("tool_$index", CallToolResult(content = listOf(TextContent("ok")), isError = false))
        }

        val snapshot = ToolActivity.snapshot()
        assertEquals(ToolActivity.MAX_CALLS, snapshot.size)
        assertEquals("tool_${ToolActivity.MAX_CALLS + 4}", snapshot.first().toolName)
        assertEquals("tool_5", snapshot.last().toolName)
        assertNull(snapshot.first().failureMessage)
    }

    @Test
    fun `thrown tool error is recorded as the error text`() {
        val result = toolResult("get_proxy_http_history") {
            throw IllegalStateException("history unavailable")
        }

        assertEquals(true, result.isError)
        assertEquals("Error: history unavailable", llmTrace(result.content))
        val call = ToolActivity.snapshot().single()
        assertFalse(call.passed)
        assertEquals("Error: history unavailable", call.failureMessage)
    }
}
