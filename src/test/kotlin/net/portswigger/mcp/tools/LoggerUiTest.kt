package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.JTable
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
