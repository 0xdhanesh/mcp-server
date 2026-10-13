package net.portswigger.mcp.tools

import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.event.MouseEvent
import java.util.concurrent.FutureTask
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.TableCellRenderer
import javax.swing.text.JTextComponent

/** Burp does not expose Logger's visible row numbers through Montoya. */
internal object LoggerUi {
    data class Entry(
        val id: Int,
        val tool: String,
        val method: String,
        val host: String,
        val path: String,
        val status: String,
        val comment: String
    )

    data class ListResult(val entries: List<Entry>?, val diagnostic: String?)

    sealed class CommentResult {
        data class Updated(val id: Int, val comment: String) : CommentResult()
        data class Unavailable(val reason: String) : CommentResult()
    }

    fun list(frame: Frame?): ListResult {
        if (frame == null) return ListResult(null, diagnostic(null))
        return RepeaterUi.preservingView(frame) {
            val entries = entriesWithLoggerVisible(frame)
            ListResult(entries, if (entries == null) diagnostic(frame) else null)
        }
    }

    internal fun diagnostic(root: Component?): String {
        if (root == null) return "Montoya did not provide Burp's suite window"
        return onEdt {
            val visible = walk(root).filterIsInstance<JTable>().filter { it.isShowing }.toList()
            if (visible.isEmpty()) return@onEdt "no visible Swing table was found in the suite window"
            val matching = visible.count { table ->
                column(table, "#") != null && column(table, "Comment") != null
            }
            val headers = visible.first().let { table ->
                (0 until table.columnCount).take(12).joinToString(", ") { table.getColumnName(it).trim() }
            }
            "${visible.size} visible Swing table(s), $matching with # and Comment; first table columns: $headers"
        }
    }

    fun setComment(frame: Frame?, id: Int, comment: String, append: Boolean): CommentResult {
        if (frame == null) return CommentResult.Unavailable("Burp's Logger window is unavailable.")
        if (id <= 0) return CommentResult.Unavailable("Logger # must be a positive number.")
        return RepeaterUi.preservingView(frame) {
            setVisibleComment(frame, id, comment, append)
        }
    }

    internal fun entries(root: Component): List<Entry>? = findTable(root)?.let(::entries)

    internal fun entriesWithLoggerVisible(root: Component): List<Entry>? = loggerTable(root)?.let(::entries)

    internal fun setVisibleComment(root: Component, id: Int, comment: String, append: Boolean): CommentResult {
        val table = loggerTable(root)
            ?: return CommentResult.Unavailable("Burp's Logger table is unavailable (${diagnostic(root)}).")
        return setComment(table, id, comment, append)
    }

    private fun loggerTable(root: Component): JTable? {
        RepeaterUi.activateSuiteTool(root, "Logger")
        return findTable(root)
    }

    internal fun setComment(table: JTable, id: Int, comment: String, append: Boolean): CommentResult {
        if (table.isEditing) {
            return CommentResult.Unavailable("Finish the current Logger comment edit before using this tool.")
        }
        val idColumn = column(table, "#") ?: return CommentResult.Unavailable("Logger # column is unavailable.")
        val commentColumn = column(table, "Comment")
            ?: return CommentResult.Unavailable("Logger Comment column is unavailable.")
        val matches = (0 until table.rowCount).filter { cellText(table, it, idColumn).toIntOrNull() == id }
        if (matches.size != 1) {
            val reason = if (matches.isEmpty()) {
                "Logger #$id is not visible. Check Logger's view filter and use list_logger_requests for available IDs."
            } else {
                "Logger #$id matches more than one row; no comment was changed."
            }
            return CommentResult.Unavailable(reason)
        }

        val row = matches.single()
        val existing = cellText(table, row, commentColumn)
        // Logger's inline editor is a single-line text field in Burp 2026.9.
        val incoming = comment.replace(Regex("[\\r\\n]+"), " ").trim()
        val next = if (append && existing.isNotBlank() && incoming.isNotBlank()) "$existing | $incoming" else incoming
        val modelRow = table.convertRowIndexToModel(row)
        val modelColumn = table.convertColumnIndexToModel(commentColumn)
        val cell = table.getCellRect(row, commentColumn, true)
        val event = MouseEvent(
            table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            cell.x + cell.width / 2, cell.y + cell.height / 2, 2, false
        )
        if (!table.editCellAt(row, commentColumn, event)) {
            return CommentResult.Unavailable("Logger's Comment cell could not be edited; no comment was changed.")
        }
        val editor = table.editorComponent?.let(::textEditor)
        if (editor == null) {
            table.cellEditor?.cancelCellEditing()
            return CommentResult.Unavailable("Logger's Comment editor is unavailable; no comment was changed.")
        }
        editor.text = next
        if (table.cellEditor?.stopCellEditing() != true) {
            table.cellEditor?.cancelCellEditing()
            return CommentResult.Unavailable("Logger did not accept the comment edit.")
        }
        val saved = table.model.getValueAt(modelRow, modelColumn)?.toString().orEmpty()
        return if (saved == next) {
            CommentResult.Updated(id, saved)
        } else {
            CommentResult.Unavailable("Logger did not confirm the comment edit; its current value is '$saved'.")
        }
    }

    private fun entries(table: JTable): List<Entry> {
        val id = column(table, "#")!!
        val tool = column(table, "Tool")
        val method = column(table, "Method")
        val host = column(table, "Host")
        val path = column(table, "Path")
        val status = column(table, "Status code")
        val comment = column(table, "Comment")!!
        return (0 until table.rowCount).mapNotNull { row ->
            val number = cellText(table, row, id).toIntOrNull() ?: return@mapNotNull null
            Entry(
                number,
                tool?.let { cellText(table, row, it) }.orEmpty(),
                method?.let { cellText(table, row, it) }.orEmpty(),
                host?.let { cellText(table, row, it) }.orEmpty(),
                path?.let { cellText(table, row, it) }.orEmpty(),
                status?.let { cellText(table, row, it) }.orEmpty(),
                cellText(table, row, comment)
            )
        }
    }

    private fun findTable(root: Component): JTable? {
        val matches = walk(root).filterIsInstance<JTable>().filter { table ->
            column(table, "#") != null && column(table, "Comment") != null &&
                listOf("Time", "Tool", "Method", "Host", "Path", "Status code")
                    .any { column(table, it) != null }
        }.toList()
        // Burp keeps tables from other tools and hidden tabs in the component tree.
        // The Logger table the user is looking at takes precedence over those tables.
        val showing = matches.filter { it.isShowing }
        return if (showing.isNotEmpty()) showing.singleOrNull() else matches.singleOrNull()
    }

    private fun column(table: JTable, name: String): Int? =
        (0 until table.columnCount).firstOrNull { table.getColumnName(it).trim().equals(name, ignoreCase = true) }

    private fun cellText(table: JTable, row: Int, column: Int): String {
        val value = table.getValueAt(row, column)
        if (value is CharSequence || value is Number) return value.toString().trim()
        val renderer: TableCellRenderer = table.getCellRenderer(row, column)
        val component = renderer.getTableCellRendererComponent(table, value, false, false, row, column)
        return (component as? javax.swing.JLabel)?.text?.trim() ?: value?.toString()?.trim().orEmpty()
    }

    private fun textEditor(root: Component): JTextComponent? = when (root) {
        is JTextComponent -> root
        is Container -> root.components.firstNotNullOfOrNull(::textEditor)
        else -> null
    }

    private fun walk(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(walk(it)) }
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
