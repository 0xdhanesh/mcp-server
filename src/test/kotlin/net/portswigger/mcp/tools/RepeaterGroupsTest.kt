package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.SwingUtilities

class RepeaterGroupsTest {
    @Test
    fun `existing MCP group receives only the named tab and restores the manual tool`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())
        var grouped = false
        val frame = JFrame()
        val top = JTabbedPane()
        val repeaterTabs = JTabbedPane()
        SwingUtilities.invokeAndWait {
            val header = JLabel("mcp-one")
            val menu = JPopupMenu()
            val add = JMenu("Add tab to group")
            add.add(JMenuItem("New tab group"))
            add.add(JMenuItem("MCP").apply { addActionListener { grouped = true } })
            menu.add(add)
            header.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) menu.show(header, e.x, e.y)
                }
            })
            repeaterTabs.addTab("MCP work", JPanel())
            repeaterTabs.addTab("manual", JPanel())
            repeaterTabs.selectedIndex = 1
            val repeater = JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(repeaterTabs, BorderLayout.CENTER)
            }
            top.addTab("Repeater", repeater)
            top.addTab("Proxy", JPanel())
            top.selectedIndex = 1
            frame.contentPane.add(top)
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        try {
            assertTrue(RepeaterGroups.tryGroup(frame, "mcp-one"))
            assertTrue(grouped)
            assertEquals(1, top.selectedIndex)
            SwingUtilities.invokeAndWait { top.selectedIndex = 0 }
            assertTrue(RepeaterGroups.tryGroup(frame, "mcp-one"))
            assertEquals(1, repeaterTabs.selectedIndex)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `new MCP group selects only the named tab and restores the manual tool`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())
        var created = false
        val frame = JFrame()
        val top = JTabbedPane()
        SwingUtilities.invokeAndWait {
            val header = JLabel("mcp-new")
            val menu = JPopupMenu()
            val add = JMenu("Add tab to group")
            add.add(JMenuItem("New tab group").apply {
                addActionListener {
                    val dialog = JDialog(frame, "Create new group", true)
                    val name = JTextField("Group 1")
                    val target = JCheckBox("mcp-new", true)
                    val unrelated = JCheckBox("manual", false)
                    val create = JButton("Create").apply {
                        addActionListener {
                            created = name.text == "MCP" && target.isSelected && !unrelated.isSelected
                            dialog.dispose()
                        }
                    }
                    val cancel = JButton("Cancel").apply { addActionListener { dialog.dispose() } }
                    dialog.contentPane.layout = java.awt.GridLayout(4, 1)
                    dialog.contentPane.add(name)
                    dialog.contentPane.add(target)
                    dialog.contentPane.add(unrelated)
                    dialog.contentPane.add(JPanel().apply { add(cancel); add(create) })
                    dialog.pack()
                    dialog.setLocationRelativeTo(frame)
                    dialog.isVisible = true
                }
            })
            menu.add(add)
            header.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) menu.show(header, e.x, e.y)
                }
            })
            top.addTab("Repeater", JPanel(BorderLayout()).apply { add(header, BorderLayout.NORTH) })
            top.addTab("Proxy", JPanel())
            top.selectedIndex = 1
            frame.contentPane.add(top)
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        try {
            assertTrue(RepeaterGroups.tryGroup(frame, "mcp-new"))
            assertTrue(created)
            assertEquals(1, top.selectedIndex)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
