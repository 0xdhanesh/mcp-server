package net.portswigger.mcp.tools

import java.awt.CardLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
