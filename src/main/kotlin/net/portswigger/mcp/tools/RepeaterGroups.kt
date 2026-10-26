package net.portswigger.mcp.tools

import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent
import java.util.ArrayDeque
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Burp 2026.9's visible tab-group menu. No Montoya group API exists. */
internal object RepeaterGroups {
    const val NAME = "MCP"

    fun tryGroup(frame: Frame?, tabName: String, timeoutMs: Long = 3_000): Boolean {
        if (frame == null || !frame.isDisplayable || SwingUtilities.isEventDispatchThread()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = runCatching {
                RepeaterUi.preservingView(frame) { groupOnce(frame, tabName) }
            }.getOrDefault(GroupAttempt.FAILED)
            when (result) {
                GroupAttempt.GROUPED -> return true
                GroupAttempt.FAILED -> return false
                GroupAttempt.NOT_READY -> Thread.sleep(100)
            }
        }
        return false
    }

    private fun groupOnce(frame: Frame, tabName: String): GroupAttempt {
        RepeaterUi.activateSuiteTool(frame, "Repeater")
        val tab = findTabCaption(frame, tabName) ?: return GroupAttempt.NOT_READY
        MenuSelectionManager.defaultManager().clearSelectedPath()
        rightClick(tab.first, tab.second)
        val popup = MenuSelectionManager.defaultManager().selectedPath
            .filterIsInstance<JPopupMenu>().firstOrNull() ?: return GroupAttempt.FAILED
        val add = popup.components.filterIsInstance<JMenu>()
            .firstOrNull { it.text?.trim().equals("Add tab to group", ignoreCase = true) }
        if (add == null) {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            return GroupAttempt.FAILED
        }
        add.isPopupMenuVisible = true
        val items = add.menuComponents.filterIsInstance<JMenuItem>()
        val existing = items.firstOrNull { it.text?.trim().equals(NAME, ignoreCase = true) }
        if (existing != null) {
            existing.doClick(0)
            MenuSelectionManager.defaultManager().clearSelectedPath()
            return GroupAttempt.GROUPED
        }
        val create = items.firstOrNull { it.text?.trim().equals("New tab group", ignoreCase = true) }
        if (create == null) {
            MenuSelectionManager.defaultManager().clearSelectedPath()
            return GroupAttempt.FAILED
        }
        var created = false
        val started = System.currentTimeMillis()
        val timer = Timer(40, null)
        timer.addActionListener {
            val dialog = Window.getWindows().filterIsInstance<JDialog>().firstOrNull {
                it.isShowing && it.title == "Create new group" && ownedBy(it, frame)
            }
            if (dialog != null) {
                timer.stop()
                created = runCatching { fillGroupDialog(dialog, tabName) }
                    .getOrElse { cancelDialog(dialog) }
            } else if (System.currentTimeMillis() - started > 2_500) {
                timer.stop()
                Window.getWindows().filterIsInstance<JDialog>()
                    .firstOrNull { it.isShowing && it.title.contains("group", ignoreCase = true) && ownedBy(it, frame) }
                    ?.let(::cancelDialog)
            }
        }
        timer.initialDelay = 0
        timer.start()
        create.doClick(0)
        // Burp's group dialog is modal. If a future Burp release makes it
        // asynchronous, cancel it instead of leaving a dialog over manual work.
        if (!created) {
            Window.getWindows().filterIsInstance<JDialog>()
                .firstOrNull { it.isShowing && it.title == "Create new group" && ownedBy(it, frame) }
                ?.let(::cancelDialog)
        }
        timer.stop()
        MenuSelectionManager.defaultManager().clearSelectedPath()
        return if (created) GroupAttempt.GROUPED else GroupAttempt.FAILED
    }

    private fun fillGroupDialog(dialog: JDialog, tabName: String): Boolean {
        val fields = descendants(dialog).filterIsInstance<JTextField>().toList()
        val selected = descendants(dialog).filterIsInstance<JCheckBox>().filter { it.isSelected }.toList()
        val chosen = selected.singleOrNull() ?: return cancelDialog(dialog)
        val captionMatches = chosen.text?.trim().equals(tabName, ignoreCase = true) ||
            descendants(chosen.parent).filterIsInstance<JLabel>().any {
                it.text?.trim().equals(tabName, ignoreCase = true)
            }
        if (!captionMatches || fields.size != 1) return cancelDialog(dialog)
        val button = descendants(dialog).filterIsInstance<JButton>()
            .firstOrNull { it.text?.trim().equals("Create", ignoreCase = true) && it.isEnabled }
            ?: return cancelDialog(dialog)
        fields.single().text = NAME
        button.doClick(0)
        return !dialog.isShowing
    }

    private fun cancelDialog(dialog: JDialog): Boolean {
        descendants(dialog).filterIsInstance<JButton>()
            .firstOrNull { it.text?.trim().equals("Cancel", ignoreCase = true) }?.doClick(0)
        if (dialog.isShowing) dialog.dispose()
        return false
    }

    private fun ownedBy(window: Window, frame: Frame): Boolean =
        generateSequence(window.owner) { it.owner }.any { it === frame }

    private fun findTabCaption(frame: Frame, name: String): Pair<Component, Point>? {
        val label = descendants(frame).filterIsInstance<JLabel>().firstOrNull {
            it.isShowing && it.text?.trim().equals(name, ignoreCase = true)
        } ?: descendants(frame).filterIsInstance<AbstractButton>().firstOrNull {
            it.isShowing && it.text?.trim().equals(name, ignoreCase = true)
        }
        if (label != null) {
            val target = generateSequence(label as Component) { it.parent }
                .take(6).firstOrNull { it.mouseListeners.isNotEmpty() && it !is Frame } ?: label
            val point = SwingUtilities.convertPoint(label, label.width / 2, label.height / 2, target)
            return target to point
        }
        for (pane in descendants(frame).filterIsInstance<JTabbedPane>()) {
            val index = (0 until pane.tabCount).firstOrNull {
                pane.getTitleAt(it).trim().equals(name, ignoreCase = true)
            } ?: continue
            if (!pane.isShowing) continue
            val bounds = pane.getBoundsAt(index)
            return pane to Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
        }
        return null
    }

    private fun rightClick(target: Component, point: Point) {
        val now = System.currentTimeMillis()
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, now, 0,
            point.x, point.y, 1, false, MouseEvent.BUTTON3))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_RELEASED, now, 0,
            point.x, point.y, 1, true, MouseEvent.BUTTON3))
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        val pending = ArrayDeque<Component>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            yield(next)
            if (next is Container) next.components.forEach(pending::add)
        }
    }

    private enum class GroupAttempt { NOT_READY, FAILED, GROUPED }
}
