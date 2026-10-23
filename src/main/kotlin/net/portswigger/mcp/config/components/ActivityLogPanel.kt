package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.tools.ToolActivity
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Bounded tool call history in the MCP suite tab. Arguments and results are sanitized before display or export.
 */
class ActivityLogPanel : JPanel(BorderLayout()) {
    private val rows = JPanel()
    private val scroll = JScrollPane()
    private val expanded = mutableSetOf<Instant>()
    private var ready = false

    private val onCalls: (List<ToolActivity.Call>) -> Unit = { _ ->
        if (SwingUtilities.isEventDispatchThread()) {
            render(ToolActivity.snapshot())
        } else {
            SwingUtilities.invokeLater { render(ToolActivity.snapshot()) }
        }
    }

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val heading = JPanel(BorderLayout())
        heading.isOpaque = false
        heading.add(Design.createSectionLabel("MCP Activity Log"), BorderLayout.WEST)
        heading.add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, Design.Spacing.SM, 0)).apply {
            isOpaque = false
            add(JButton("Clear logs").apply {
                addActionListener {
                    expanded.clear()
                    ToolActivity.clear()
                }
            })
            add(JButton("Export logs").apply {
                addActionListener { exportLogs() }
            })
        }, BorderLayout.EAST)
        val subtitle = JLabel("Tool inputs and outputs. Stored secrets are redacted.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(Design.Spacing.SM, 0, Design.Spacing.SM, 0)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heading, BorderLayout.NORTH)
            add(subtitle, BorderLayout.SOUTH)
        }

        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        rows.alignmentX = LEFT_ALIGNMENT
        rows.background = Design.Colors.listBackground

        scroll.setViewportView(rows)
        scroll.border = BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1)
        scroll.background = Design.Colors.listBackground
        scroll.viewport.background = Design.Colors.listBackground
        scroll.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scroll.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scroll.verticalScrollBar.unitIncrement = 16
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                rows.revalidate()
            }
        })

        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        ready = true
        ToolActivity.addListener(onCalls)
        render(ToolActivity.snapshot())
    }

    fun dispose() {
        ToolActivity.removeListener(onCalls)
    }

    private fun exportLogs() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export MCP activity log"
            selectedFile = java.io.File("mcp-activity.jsonl")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        if (Files.exists(path) && JOptionPane.showConfirmDialog(
                this, "Replace the selected file?", "Export MCP activity log", JOptionPane.YES_NO_OPTION
            ) != JOptionPane.YES_OPTION) return
        try {
            Files.writeString(path, ToolActivity.exportJsonl(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            JOptionPane.showMessageDialog(this, "Could not export the activity log.", "Export failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    override fun updateUI() {
        super.updateUI()
        if (!ready) return
        scroll.border = BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1)
        scroll.background = Design.Colors.listBackground
        scroll.viewport.background = Design.Colors.listBackground
        rows.background = Design.Colors.listBackground
        render(ToolActivity.snapshot())
    }

    private fun render(calls: List<ToolActivity.Call>) {
        val bar = scroll.verticalScrollBar
        val followLatest = bar.value <= bar.unitIncrement

        rows.removeAll()
        if (calls.isEmpty()) {
            rows.add(JLabel("No tool calls yet.").apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                border = BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
                alignmentX = LEFT_ALIGNMENT
            })
        } else {
            expanded.retainAll(calls.mapTo(mutableSetOf()) { it.timestamp })
            calls.forEach { call ->
                rows.add(ActivityRow(call, call.timestamp in expanded) { open ->
                    if (open) expanded.add(call.timestamp) else expanded.remove(call.timestamp)
                })
            }
            rows.add(Box.createVerticalGlue())
        }
        rows.revalidate()
        rows.repaint()
        if (followLatest) {
            SwingUtilities.invokeLater { bar.value = 0 }
        }
    }
}

private class ActivityRow(call: ToolActivity.Call, open: Boolean, onOpenChanged: (Boolean) -> Unit) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Design.Colors.outlineVariant),
            BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD)
        )

        val line = JLabel(call.line()).apply {
            font = Design.Typography.labelLarge
            foreground = if (call.passed) Design.Colors.onSurface else Design.Colors.error
        }
        val toggle = JButton(if (open) "Hide details" else "Details")
        val summary = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(line, BorderLayout.CENTER)
            add(toggle, BorderLayout.EAST)
        }
        add(summary, BorderLayout.NORTH)

        val details = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(detailText("Input", call.input))
            add(Box.createVerticalStrut(Design.Spacing.SM))
            add(detailText("Output", call.output))
            isVisible = open
        }
        add(details, BorderLayout.CENTER)
        toggle.addActionListener {
            details.isVisible = !details.isVisible
            toggle.text = if (details.isVisible) "Hide details" else "Details"
            onOpenChanged(details.isVisible)
            revalidate()
            repaint()
        }
    }

    override fun getMaximumSize(): Dimension {
        val preferred = preferredSize
        return Dimension(Int.MAX_VALUE, preferred.height)
    }
}

private fun detailText(label: String, text: String): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    add(JLabel(label).apply {
        font = Design.Typography.labelLarge
        foreground = Design.Colors.onSurfaceVariant
    }, BorderLayout.NORTH)
    val area = JTextArea(text).apply {
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        isEditable = false
        lineWrap = false
        caretPosition = 0
    }
    add(JScrollPane(area).apply {
        preferredSize = Dimension(300, 150)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }, BorderLayout.CENTER)
}
