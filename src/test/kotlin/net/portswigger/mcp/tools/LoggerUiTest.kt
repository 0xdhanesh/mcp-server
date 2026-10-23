package net.portswigger.mcp.tools

import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import net.portswigger.mcp.security.VariableStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class LoggerUiTest {
    @Test
    fun `stored token is redacted before writing a Logger comment`() {
        val canary = "eyJhbGciOiJub25l.canary.signature"
        ToolResultRedaction.store = VariableStore().apply { put("canary", canary, "admin", "api.target") }
        try {
            onEdt {
                val table = loggerTable()
                val result = LoggerUi.setComment(table, 4, "observed $canary", append = false)
                assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, result)
                assertEquals("observed {{canary}}", table.model.getValueAt(0, 6))
            }
        } finally {
            ToolResultRedaction.store = null
        }
    }

    @Test
    fun `lists visible Logger rows and edits the exact visible number after sorting`() = onEdt {
        val table = loggerTable()
        table.autoCreateRowSorter = true
        table.rowSorter.toggleSortOrder(0)
        table.rowSorter.toggleSortOrder(0)
        val root = JPanel().apply { add(table) }

        assertEquals(listOf(6, 4), LoggerUi.entries(root)?.map { it.id })
        val updated = LoggerUi.setComment(table, 4, "SQL injection confirmed", append = true)

        assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, updated, updated.toString())
        assertEquals("existing | SQL injection confirmed", (updated as LoggerUi.CommentResult.Updated).comment)
        assertEquals("existing | SQL injection confirmed", table.model.getValueAt(0, 6))
        assertEquals("other", table.model.getValueAt(1, 6))
    }

    @Test
    fun `does not edit a missing or ambiguous Logger number`() = onEdt {
        val table = loggerTable()
        assertInstanceOf(LoggerUi.CommentResult.Unavailable::class.java, LoggerUi.setComment(table, 5, "wrong", false))
        table.model.setValueAt(4, 1, 0)
        val ambiguous = LoggerUi.setComment(table, 4, "wrong", false)
        assertTrue((ambiguous as LoggerUi.CommentResult.Unavailable).reason.contains("more than one"))
        assertEquals("existing", table.model.getValueAt(0, 6))
        assertEquals("other", table.model.getValueAt(1, 6))
    }

    @Test
    fun `selects Logger number before editing and restores the prior selection`() = onEdt {
        lateinit var table: JTable
        val model = object : DefaultTableModel(
            arrayOf(
                arrayOf<Any>(1, "Repeater", "GET", "localhost", "/same", "200", "manual"),
                arrayOf<Any>(47, "Extensions", "GET", "localhost", "/same", "200", "extension")
            ),
            arrayOf("#", "Tool", "Method", "Host", "Path", "Status code", "Comment")
        ) {
            override fun setValueAt(value: Any?, row: Int, column: Int) {
                // Model the Logger editor writing to the selected request.
                val destination = if (column == 6) table.convertRowIndexToModel(table.selectedRow) else row
                super.setValueAt(value, destination, column)
            }
        }
        table = JTable(model)
        table.setRowSelectionInterval(0, 0)

        val result = LoggerUi.setComment(table, 47, "checked", append = false)

        assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, result, result.toString())
        assertEquals("manual", model.getValueAt(0, 6))
        assertEquals("checked", model.getValueAt(1, 6))
        assertEquals(0, table.selectedRow)
    }

    @Test
    fun `filters extension rows before pagination and refuses a Proxy row with the same path`() = onEdt {
        val table = JTable(DefaultTableModel(
            arrayOf(
                arrayOf<Any>(1, "Proxy", "GET", "localhost", "/", "200", "manual"),
                arrayOf<Any>(2, "Proxy", "GET", "localhost", "/app.js", "200", ""),
                arrayOf<Any>(6, "Proxy", "GET", "localhost", "/other.js", "200", ""),
                arrayOf<Any>(47, "Extensions", "GET", "localhost", "/", "200", "extension")
            ),
            arrayOf("#", "Tool", "Method", "Host", "Path", "Status code", "Comment")
        ))
        val root = JPanel().apply { add(table) }

        assertEquals(listOf(47), LoggerUi.entries(root, sourceTool = "Extensions")?.take(3)?.map { it.id })
        assertEquals(listOf(47), LoggerUi.entries(root, loggerId = 47)?.take(3)?.map { it.id })
        assertEquals(emptyList<Int>(), LoggerUi.entries(root, sourceTool = "Proxy", loggerId = 47)?.map { it.id })
        val wrongSource = LoggerUi.setComment(table, 1, "wrong", false, sourceTool = "Extensions")
        assertTrue((wrongSource as LoggerUi.CommentResult.Unavailable).reason.contains("belongs to 'Proxy'"))
        assertEquals("manual", table.model.getValueAt(0, 6))

        val updated = LoggerUi.setComment(table, 47, "checked", false, sourceTool = "Extensions")
        assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, updated)
        assertEquals("manual", table.model.getValueAt(0, 6))
        assertEquals("checked", table.model.getValueAt(3, 6))
    }

    @Test
    fun `finds the visible Logger table when another matching table is hidden`() = onEdt {
        val hidden = loggerTable()
        val visible = object : JTable(DefaultTableModel(
            arrayOf(arrayOf<Any>(9, "12:00", "visible")),
            arrayOf("#", "Time", "Comment")
        )) {
            override fun isShowing() = true
        }
        val root = JPanel().apply {
            add(hidden)
            add(visible)
        }

        assertEquals(listOf(9), LoggerUi.entries(root)?.map { it.id })
        assertEquals("visible", LoggerUi.entries(root)?.single()?.comment)
    }

    @Test
    fun `does not guess when two Logger-like tables are visible`() = onEdt {
        fun visibleTable() = object : JTable(DefaultTableModel(
            arrayOf(arrayOf<Any>(9, "12:00", "visible")),
            arrayOf("#", "Time", "Comment")
        )) {
            override fun isShowing() = true
        }
        val root = JPanel().apply {
            add(visibleTable())
            add(visibleTable())
        }

        assertEquals(null, LoggerUi.entries(root))
        assertTrue(LoggerUi.diagnostic(root).contains("2 visible Swing table(s), 2 with # and Comment"))
    }

    @Test
    fun `lists and comments when Logger tab is loaded only after selection and restores MCP tab`() = onEdt {
        val logger = JPanel()
        val tabs = JTabbedPane().apply {
            addTab("MCP v1.3.6", JPanel())
            addTab("Logger", logger)
            selectedIndex = 0
            addChangeListener {
                if (selectedIndex == 1 && logger.componentCount == 0) logger.add(loggerTable())
            }
        }
        val root = JPanel().apply { add(tabs) }
        assertEquals(null, LoggerUi.entries(root))

        val listed = RepeaterUi.preservingComponentView(root) { LoggerUi.entriesWithLoggerVisible(root) }
        assertEquals(listOf(4, 6), listed?.map { it.id })
        assertEquals(0, tabs.selectedIndex)

        val edited = RepeaterUi.preservingComponentView(root) {
            LoggerUi.setVisibleComment(root, 4, "checked", append = false)
        }
        assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, edited)
        assertEquals("checked", loggerTableValue(logger, 0, 6))
        assertEquals(0, tabs.selectedIndex)
    }

    @Test
    fun `opens a Logger label backed by a card and restores the previous card`() = onEdt {
        val mcp = JPanel()
        val logger = JPanel()
        val cards = JPanel(CardLayout()).apply {
            add(mcp, "MCP")
            add(logger, "Logger")
            (layout as CardLayout).show(this, "MCP")
        }
        val loggerLabel = object : JLabel("Logger") {
            override fun isShowing() = true
        }.apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (logger.componentCount == 0) logger.add(loggerTable())
                    (cards.layout as CardLayout).show(cards, "Logger")
                }
            })
        }
        val root = JPanel().apply {
            add(loggerLabel)
            add(cards)
        }

        val listed = RepeaterUi.preservingComponentView(root) { LoggerUi.entriesWithLoggerVisible(root) }
        assertEquals(listOf(4, 6), listed?.map { it.id })
        assertTrue(mcp.isVisible)
        assertTrue(!logger.isVisible)
    }

    @Test
    fun `lists and comments through a minimized suite without a showing table`() = onEdt {
        val mcp = JPanel().apply { add(loggerTable()) }
        val logger = JPanel()
        val cards = JPanel(CardLayout()).apply {
            add(mcp, "MCP")
            add(logger, "Logger")
            (layout as CardLayout).show(this, "MCP")
        }
        val loggerLabel = object : JLabel("Logger") {
            override fun isDisplayable() = true
        }.apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (logger.componentCount == 0) logger.add(loggerTable())
                    (cards.layout as CardLayout).show(cards, "Logger")
                }
            })
        }
        val root = object : JPanel() {
            override fun isDisplayable() = true
        }.apply {
            add(loggerLabel)
            add(cards)
        }
        assertTrue(!root.isShowing)
        assertTrue(!loggerLabel.isShowing)

        val listed = RepeaterUi.preservingComponentView(root) { LoggerUi.entriesWithLoggerVisible(root) }
        assertEquals(listOf(4, 6), listed?.map { it.id })
        assertTrue(mcp.isVisible)
        assertTrue(!logger.isVisible)

        val edited = RepeaterUi.preservingComponentView(root) {
            LoggerUi.setVisibleComment(root, 4, "background check", append = false)
        }
        assertInstanceOf(LoggerUi.CommentResult.Updated::class.java, edited)
        assertEquals("background check", loggerTableValue(logger, 0, 6))
        assertTrue(mcp.isVisible)
        assertTrue(!logger.isVisible)
    }

    private fun loggerTableValue(root: JPanel, row: Int, column: Int): Any? =
        (root.getComponent(0) as JTable).model.getValueAt(row, column)

    private fun loggerTable() = JTable(DefaultTableModel(
        arrayOf(
            arrayOf<Any>(4, "Extensions", "GET", "localhost", "/search", "200", "existing"),
            arrayOf<Any>(6, "Repeater", "GET", "localhost", "/search", "200", "other")
        ),
        arrayOf("#", "Tool", "Method", "Host", "Path", "Status code", "Comment")
    ))

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}
