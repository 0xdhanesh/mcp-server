package net.portswigger.mcp.config

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import net.portswigger.mcp.tools.ToolActivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class ConfigUiTabsTest {
    @Test
    fun `MCP sections are separate scrollable tabs without installation controls`() {
        SwingUtilities.invokeAndWait {
            val ui = newUi()
            try {
                val tabs = walk(ui.component).filterIsInstance<JTabbedPane>().single()
                assertEquals(listOf(
                    "Activity Log", "Token Request", "Auto-Approved HTTP Requests", "MCP Settings"
                ), (0 until tabs.tabCount).map(tabs::getTitleAt))
                assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.tabLayoutPolicy)

                (1 until tabs.tabCount).forEach { index ->
                    assertTrue(tabs.getComponentAt(index) is JScrollPane)
                }
                val settingsLabels = walk(tabs.getComponentAt(3)).filterIsInstance<JLabel>().map { it.text }
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

    private fun newUi() = ConfigUi(McpConfig(mockk<PersistedObject>(relaxed = true), mockk<Logging>(relaxed = true)))

    private fun walk(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::walk)
}
