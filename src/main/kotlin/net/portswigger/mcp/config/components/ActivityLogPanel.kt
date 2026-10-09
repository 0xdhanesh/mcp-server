package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.tools.ToolActivity
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.plaf.TextUI
import javax.swing.text.View

/**
 * Lists MCP tool calls in the suite tab. Each row is `HH:mm:ss - Pass|Fail - tool`.
 * A failed call also shows the text that was returned to the model.
 */
class ActivityLogPanel : JPanel(BorderLayout()) {
    private val rows = JPanel()
    private val scroll = JScrollPane()
    private var ready = false

    private val onCalls: (List<ToolActivity.Call>) -> Unit = { calls ->
        if (SwingUtilities.isEventDispatchThread()) {
            render(calls)
        } else {
            SwingUtilities.invokeLater { render(calls) }
        }
    }

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val heading = JPanel()
        heading.layout = BoxLayout(heading, BoxLayout.Y_AXIS)
        heading.isOpaque = false
        heading.add(Design.createSectionLabel("Activity"))
        heading.add(Box.createVerticalStrut(Design.Spacing.SM))
        heading.add(JLabel("Newest first. Failures include the message sent to the LLM.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        heading.add(Box.createVerticalStrut(Design.Spacing.SM))

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

        add(heading, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        ready = true
        ToolActivity.addListener(onCalls)
        render(ToolActivity.snapshot())
    }

    fun dispose() {
        ToolActivity.removeListener(onCalls)
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
            calls.forEach { call -> rows.add(ActivityRow(call)) }
            rows.add(Box.createVerticalGlue())
        }
        rows.revalidate()
        rows.repaint()
        if (followLatest) {
            SwingUtilities.invokeLater { bar.value = 0 }
        }
    }
}

private class ActivityRow(call: ToolActivity.Call) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Design.Colors.outlineVariant),
            BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD)
        )

        add(JLabel(call.line()).apply {
            font = Design.Typography.labelLarge
            foreground = if (call.passed) Design.Colors.onSurface else Design.Colors.error
        }, BorderLayout.NORTH)

        val trace = call.failureMessage?.takeIf { it.isNotBlank() }
        if (trace != null) {
            add(WrappingText(trace).apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.error
                border = BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.SM, 0, 0)
            }, BorderLayout.CENTER)
        }
    }

    override fun getMaximumSize(): Dimension {
        val preferred = preferredSize
        return Dimension(Int.MAX_VALUE, preferred.height)
    }
}

private class WrappingText(text: String) : JTextArea(text) {
    init {
        lineWrap = true
        wrapStyleWord = true
        columns = 24
        isEditable = false
        isOpaque = false
        isFocusable = true
    }

    override fun getPreferredSize(): Dimension {
        val width = parent?.width?.minus(insets.left + insets.right)?.takeIf { it > 40 }
            ?: return super.getPreferredSize()
        val root = (ui as? TextUI)?.getRootView(this) ?: return super.getPreferredSize()
        root.setSize(width.toFloat(), Float.MAX_VALUE)
        val height = root.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
        return Dimension(width, height.coerceAtLeast(font.size + insets.top + insets.bottom))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun addNotify() {
        super.addNotify()
        (parent as? Component)?.let { revalidate() }
    }
}
