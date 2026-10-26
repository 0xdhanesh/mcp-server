package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.security.VariableStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

class ToolActivityTest {
    @BeforeEach
    @AfterEach
    fun clearActivity() {
        ToolActivity.clear()
        ToolResultRedaction.store = null
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

    @Test
    fun `activity keeps full arguments and result and exports one JSON object per line`() {
        val arguments = JsonObject(mapOf("url" to JsonPrimitive("https://example.test/a"), "count" to JsonPrimitive(2)))
        toolResult("example_tool", arguments) {
            CallToolResult(content = listOf(TextContent("first line\nsecond line")), isError = false)
        }

        val call = ToolActivity.snapshot().single()
        assertTrue(call.input.contains("https://example.test/a"))
        assertTrue(call.output.contains("first line\\nsecond line"))
        val lines = ToolActivity.exportJsonl().trimEnd().lines()
        assertEquals(1, lines.size)
        val exported = Json.parseToJsonElement(lines.single()) as JsonObject
        assertEquals("example_tool", (exported["tool"] as JsonPrimitive).content)
        assertTrue((exported["input"] as JsonPrimitive).content.contains("https://example.test/a"))
        assertTrue((exported["output"] as JsonPrimitive).content.contains("first line"))
    }

    @Test
    fun `session secret stays out of activity display and export including earlier entries`() {
        val store = VariableStore()
        ToolResultRedaction.store = store
        store.onValueStored = { ToolActivity.scrubWith(store) }
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYW5hcnkifQ.sig12345"

        ToolActivity.record("send_http1_request", CallToolResult(content = listOf(TextContent("echo $token"))),
            JsonObject(mapOf("content" to JsonPrimitive("Authorization: Bearer $token"))))
        store.put("canary", token, "user", "example.test")
        ToolActivity.record("send_with_vars", CallToolResult(content = listOf(TextContent("echo $token"))),
            JsonObject(mapOf("request" to JsonPrimitive("Authorization: Bearer $token"))))
        ToolActivity.record("set_session_var", CallToolResult(content = listOf(TextContent("Stored {{canary}}"))),
            JsonObject(mapOf("name" to JsonPrimitive("canary"), "value" to JsonPrimitive(token))))

        ToolActivity.snapshot().forEach { call ->
            assertFalse(call.input.contains(token))
            assertFalse(call.output.contains(token))
            assertFalse(call.requestContent().contains(token))
            assertFalse(call.responseContent().contains(token))
            assertFalse(call.fullCommunication().contains(token))
        }
        val export = ToolActivity.exportJsonl()
        assertFalse(export.contains(token))
        assertTrue(export.contains("{{canary}}"))
        assertTrue(ToolActivity.snapshot().first { it.toolName == "send_with_vars" }.input.contains("Bearer {{canary}}"))
        assertEquals(3, export.trimEnd().lines().size)
        store.close()
    }

    @Test
    fun `token request body credentials are masked in activity and export`() {
        val password = "private-login-password"
        val input = JsonObject(mapOf(
            "request" to JsonPrimitive("POST /login HTTP/1.1\r\nHost: auth.target\r\n\r\n{\"password\":\"$password\"}")
        ))
        ToolActivity.record("acquire_session_var", CallToolResult(content = listOf(TextContent("Stored {{session}}"))), input)
        assertFalse(ToolActivity.snapshot().single().input.contains(password))
        assertFalse(ToolActivity.exportJsonl().contains(password))
    }

    @Test
    fun `unstored response credentials are redacted before the MCP result leaves the server`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYW5hcnkifQ.sig12345"
        val result = toolResult("send_repeater_request") {
            CallToolResult(content = listOf(TextContent(
                "HTTP/1.1 200 OK\r\nSet-Cookie: session=private-cookie\r\n\r\n" +
                    "{\"access_token\":\"$token\"}"
            )))
        }
        val visible = llmTrace(result.content)
        assertFalse(visible.contains("private-cookie"))
        assertFalse(visible.contains(token))
        assertTrue(visible.contains("<redacted>"))
    }
}
