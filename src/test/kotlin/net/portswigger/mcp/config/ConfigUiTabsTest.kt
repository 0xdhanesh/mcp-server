package net.portswigger.mcp.config

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.tools.ToolActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ConfigUiTabsTest {
    @Test
    fun `MCP sections are separate scrollable tabs without installation controls`() {
        SwingUtilities.invokeAndWait {
            val ui = newUi()
            try {
                val tabs = walk(ui.component).filterIsInstance<JTabbedPane>().single()
                assertEquals(listOf(
                    "Activity Log", "Token Request", "Auto-Approved HTTP Requests", "", "MCP Settings"
                ), (0 until tabs.tabCount).map(tabs::getTitleAt))
                assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.tabLayoutPolicy)

                val separatorIndex = 3
                assertFalse(tabs.isEnabledAt(separatorIndex))
                assertTrue(tabs.getTabComponentAt(separatorIndex) is JSeparator)

                (1 until tabs.tabCount).filter { it != separatorIndex }.forEach { index ->
                    assertTrue(tabs.getComponentAt(index) is JScrollPane)
                }
                val settingsIndex = tabs.indexOfTab("MCP Settings")
                assertTrue((tabs.getTabComponentAt(settingsIndex) as JLabel).icon != null)
                val settingsLabels = walk(tabs.getComponentAt(settingsIndex)).filterIsInstance<JLabel>().map { it.text }
                assertTrue("Server Configuration" in settingsLabels)
                assertTrue("Advanced Settings" in settingsLabels)
                assertTrue(walk(ui.component).filterIsInstance<JButton>().none { it.text.contains("Install") })
            } finally {
                ui.cleanup()
            }
        }
    }

    @Test
    fun `clear logs empties the activity view and retained export`() {
        ToolActivity.clear()
        ToolActivity.record("example_tool", CallToolResult(content = listOf(TextContent("ok"))))
        SwingUtilities.invokeAndWait {
            val ui = newUi()
            try {
                val clearButton = walk(ui.component).filterIsInstance<JButton>().single { it.text == "Clear logs" }
                clearButton.doClick()
                assertTrue(ToolActivity.snapshot().isEmpty())
                assertEquals("", ToolActivity.exportJsonl())
                assertTrue(walk(ui.component).filterIsInstance<JLabel>().any { it.text == "No tool calls yet." })
            } finally {
                ui.cleanup()
                ToolActivity.clear()
            }
        }
    }

    @Test
    fun `activity details show request and response content plus full communication`() {
        ToolActivity.clear()
        val request = "curl http://127.0.0.1:18080/api/whoami -H 'Authorization: Bearer {{session}}'"
        val response = "status=200\n{\"subject\":\"{{usrname}}\"}"
        ToolActivity.record("send_with_vars", CallToolResult(content = listOf(TextContent(response))),
            JsonObject(mapOf(
                "request" to JsonPrimitive(request),
                "identity" to JsonPrimitive("alice"),
                "host" to JsonPrimitive("127.0.0.1")
            )))
        SwingUtilities.invokeAndWait {
            val ui = newUi()
            try {
                val tabs = walk(ui.component).filterIsInstance<JTabbedPane>().single()
                val activity = tabs.getComponentAt(tabs.indexOfTab("Activity Log"))
                walk(activity).filterIsInstance<JButton>().single { it.text == "Details" }.doClick()
                val labels = walk(activity).filterIsInstance<JLabel>().map { it.text }
                assertTrue("Request content" in labels)
                assertTrue("Response content" in labels)
                assertTrue("Full communication (redacted)" in labels)
                val areas = walk(activity).filterIsInstance<JTextArea>()
                assertEquals(request, areas[0].text)
                assertEquals(response, areas[1].text)
                assertTrue(areas[2].text.contains("\"request\""))
                assertTrue(areas[2].text.contains("\"content\""))
                assertTrue(areas[2].text.contains("→"))
            } finally {
                ui.cleanup()
                ToolActivity.clear()
            }
        }
    }

    private fun newUi() = ConfigUi(McpConfig(mockk<PersistedObject>(relaxed = true), mockk<Logging>(relaxed = true)))

    private fun walk(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::walk)
}
