package net.portswigger.mcp.tools

import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder
import javax.swing.text.JTextComponent

/**
 * Best-effort bridge onto the Repeater window.
 *
 * Official Repeater methods only call [burp.api.montoya.repeater.Repeater.sendToRepeater].
 * They do not click Send and they do not set the Notes field. These helpers
 * look for those Swing controls after the official call has opened the tab.
 * They never issue a second request.
 *
 * Burp's Send control is a button whose text is empty. The word "Send" is the
 * tooltip, and that tooltip is hidden while the button is disabled. The Notes
 * editor is a text pane behind a Notes tab, not a titled border.
 */
internal sealed class RepeaterUiSend {
    data class Response(val text: String, val via: String = "Repeater Send") : RepeaterUiSend()
    data class ClickedUnreadable(val detail: String) : RepeaterUiSend()
    data class NotAvailable(val detail: String) : RepeaterUiSend()
}

internal object RepeaterUi {
    fun trySend(frame: Frame?, tabName: String, timeoutMs: Long = 15_000): RepeaterUiSend {
        if (frame == null) {
            return RepeaterUiSend.NotAvailable("Burp's window is not available, so Repeater Send could not be used.")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var fired: FiredSend? = null
        while (fired == null && System.currentTimeMillis() < deadline) {
            val elapsed = timeoutMs - (deadline - System.currentTimeMillis())
            fired = runCatching {
                onEdt {
                    val tab = revealRepeaterTab(frame, tabName) ?: return@onEdt null
                    val button = findSendButtonForTab(tab)
                    val editor = requestEditor(tab)
                    val shortcut = editor != null && hasSendShortcut(editor)
                    val readyButton = button?.takeIf { it.isEnabled }
                    if (readyButton == null && !(shortcut && (button == null || elapsed > 1_500))) {
                        return@onEdt null
                    }
                    val before = editorTexts(tab)
                    val via = if (readyButton != null) {
                        readyButton.doClick(0)
                        "Send button"
                    } else if (editor != null && fireSendShortcut(editor)) {
                        "Send shortcut"
                    } else {
                        return@onEdt null
                    }
                    FiredSend(tab, before, via)
                }
            }.getOrNull()
            if (fired == null) Thread.sleep(150)
        }

        val sent = fired ?: return RepeaterUiSend.NotAvailable(diagnose(frame, tabName))
        val responseDeadline = System.currentTimeMillis() + timeoutMs
        var latestHttp = ""
        var latest = ""
        while (System.currentTimeMillis() < responseDeadline) {
            val texts = runCatching { onEdt { editorTexts(sent.tab) } }.getOrDefault(emptyList())
            val fresh = texts.firstOrNull { looksLikeHttpResponse(it) && it !in sent.before }
            if (fresh != null) return RepeaterUiSend.Response(fresh, sent.via)
            latestHttp = texts.firstOrNull { looksLikeHttpResponse(it) }.orEmpty()
            latest = texts.maxByOrNull { it.length }.orEmpty()
            Thread.sleep(200)
        }

        if (latestHttp.isNotBlank() && latestHttp !in sent.before) {
            return RepeaterUiSend.Response(latestHttp, sent.via)
        }
        if (latestHttp.isNotBlank()) return RepeaterUiSend.Response(latestHttp, sent.via)
        return if (latest.isBlank()) {
            RepeaterUiSend.ClickedUnreadable(
                "${sent.via} ran in Repeater tab '$tabName', but no response text was visible yet."
            )
        } else {
            RepeaterUiSend.ClickedUnreadable(
                "${sent.via} ran in Repeater tab '$tabName'. The response pane did not look like an HTTP response yet.\n$latest"
            )
        }
    }

    fun trySetNotes(frame: Frame?, tabName: String, notes: String, timeoutMs: Long = 3_000): Boolean {
        if (frame == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val wrote = runCatching {
                onEdt {
                    val tab = revealRepeaterTab(frame, tabName) ?: return@onEdt false
                    val field = findNotesField(tab) ?: return@onEdt false
                    field.text = notes
                    if (field.text != notes) {
                        runCatching {
                            field.document.remove(0, field.document.length)
                            field.document.insertString(0, notes, null)
                        }
                    }
                    if (field.text != notes) return@onEdt false
                    commitEditor(field)
                    true
                }
            }.getOrDefault(false)
            if (wrote) return true
            Thread.sleep(150)
        }
        return false
    }

    /**
     * Reads the request currently shown in the named Repeater tab.
     * Used when the official HTTP send has to stand in for the Send button,
     * so the bytes match what the tab is showing.
     */
    fun tryReadRequest(frame: Frame?, tabName: String): String? {
        if (frame == null) return null
        return runCatching {
            onEdt {
                val tab = revealRepeaterTab(frame, tabName) ?: return@onEdt null
                requestEditor(tab)?.text
            }
        }.getOrNull()
    }

    fun suiteFrameOrNull(frameProvider: () -> Frame?): Frame? = runCatching { frameProvider() }.getOrNull()
}

private data class FiredSend(val tab: Component, val before: Set<String>, val via: String)

internal fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val holder = arrayOfNulls<Any>(1)
    var thrown: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            holder[0] = block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T
}

private fun revealRepeaterTab(frame: Component, tabName: String): Component? {
    selectSuiteTab(frame, "Repeater")
    val suite = suiteContent(frame, "Repeater") ?: frame
    selectSubTab(suite, tabName)?.let { return it }
    clickTabHeader(suite, tabName)
    selectSubTab(suite, tabName)?.let { return it }
    selectSubTab(frame, tabName)?.let { return it }
    return null
}

private fun selectSuiteTab(frame: Component, title: String) {
    val pane = walk(frame).filterIsInstance<JTabbedPane>().firstOrNull { candidate ->
        indices(candidate).any { tabTitle(candidate, it).equals(title, ignoreCase = true) }
    } ?: return
    val index = indices(pane).first { tabTitle(pane, it).equals(title, ignoreCase = true) }
    pane.selectedIndex = index
}

private fun suiteContent(frame: Component, title: String): Component? {
    val pane = walk(frame).filterIsInstance<JTabbedPane>().firstOrNull { candidate ->
        indices(candidate).any { tabTitle(candidate, it).equals(title, ignoreCase = true) }
    } ?: return null
    val index = indices(pane).first { tabTitle(pane, it).equals(title, ignoreCase = true) }
    return pane.getComponentAt(index)
}

private fun selectSubTab(root: Component, tabName: String): Component? {
    val panes = walk(root).filterIsInstance<JTabbedPane>().toList()
    val exact = panes.firstNotNullOfOrNull { pane ->
        val index = indices(pane).firstOrNull { tabTitle(pane, it).equals(tabName, ignoreCase = true) }
        if (index == null) null else pane to index
    }
    val partial = exact ?: panes.firstNotNullOfOrNull { pane ->
        val index = indices(pane).firstOrNull { tabMatches(pane, it, tabName) }
        if (index == null) null else pane to index
    } ?: return null
    partial.first.selectedIndex = partial.second
    return partial.first.getComponentAt(partial.second)
}

private fun tabMatches(pane: JTabbedPane, index: Int, tabName: String): Boolean {
    if (tabTitle(pane, index).contains(tabName, ignoreCase = true)) return true
    val tip = runCatching { pane.getToolTipTextAt(index) }.getOrNull()
    if (tip?.contains(tabName, ignoreCase = true) == true) return true
    val header = runCatching { pane.getTabComponentAt(index) }.getOrNull() ?: return false
    return walk(header).any { componentLabel(it).contains(tabName.lowercase()) }
}

private fun tabTitle(pane: JTabbedPane, index: Int): String {
    return runCatching { pane.getTitleAt(index) }.getOrNull().orEmpty()
}

private fun clickTabHeader(root: Component, tabName: String) {
    val wanted = tabName.lowercase()
    val header = walk(root).firstOrNull { component ->
        component is JLabel && componentLabel(component) == wanted
    } ?: return
    val button = generateSequence(header) { it.parent }.filterIsInstance<AbstractButton>().firstOrNull()
    if (button != null) {
        button.doClick(0)
    }
}

private fun findSendButtonForTab(tab: Component): AbstractButton? {
    findSendButton(tab)?.let { return it }
    val pane = generateSequence(tab.parent) { it.parent }.filterIsInstance<JTabbedPane>().firstOrNull() ?: return null
    val holder = pane.parent ?: return null
    return walk(holder, descendInto = { it !== pane }).filterIsInstance<AbstractButton>()
        .filter { isPrimarySend(componentLabel(it)) }
        .sortedWith(sendButtonOrder)
        .firstOrNull()
}

private val sendButtonOrder = compareBy<AbstractButton>(
    { !it.isEnabled },
    { !it.isShowing },
    { componentLabel(it).length }
)

private fun findSendButton(root: Component): AbstractButton? {
    return walk(root).filterIsInstance<AbstractButton>()
        .filter { isPrimarySend(componentLabel(it)) }
        .sortedWith(sendButtonOrder)
        .firstOrNull()
}

private fun isPrimarySend(label: String): Boolean {
    if (label.isBlank()) return false
    val blocked = listOf("send group", "send to", "intruder", "comparer", "scanner", "organizer", "decoder")
    if (blocked.any { label.contains(it) }) return false
    return Regex("(^| )send( |$)").containsMatchIn(label)
}

private fun requestEditor(root: Component): JTextComponent? {
    return walk(root).filterIsInstance<JTextComponent>()
        .filter { looksLikeHttpRequest(it.text.orEmpty()) }
        .sortedByDescending { if (it.isShowing) 1 else 0 }
        .firstOrNull()
}

private fun hasSendShortcut(editor: JTextComponent): Boolean = sendAction(editor) != null

private fun fireSendShortcut(editor: JTextComponent): Boolean {
    val found = sendAction(editor) ?: return false
    found.second.actionPerformed(ActionEvent(editor, ActionEvent.ACTION_PERFORMED, found.first.toString()))
    return true
}

private fun sendAction(editor: JTextComponent): Pair<Any, javax.swing.Action>? {
    val masks = listOf(
        Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
        InputEvent.CTRL_DOWN_MASK,
        InputEvent.META_DOWN_MASK
    ).distinct()
    val conditions = intArrayOf(
        JComponent.WHEN_IN_FOCUSED_WINDOW,
        JComponent.WHEN_FOCUSED,
        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
    )
    for (mask in masks) {
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, mask)
        for (condition in conditions) {
            val key = runCatching { editor.getInputMap(condition).get(stroke) }.getOrNull() ?: continue
            val action = editor.actionMap.get(key) ?: continue
            return key to action
        }
    }
    return null
}

private fun findNotesField(tab: Component): JTextComponent? {
    val titled = walk(tab).firstOrNull { component ->
        val border = (component as? JComponent)?.border
        border is TitledBorder && border.title?.contains("Notes", ignoreCase = true) == true
    }
    if (titled != null) {
        walk(titled).filterIsInstance<JTextComponent>().firstOrNull { !isHttpMessage(it) }?.let { return it }
    }

    walk(tab).filterIsInstance<JTextComponent>().firstOrNull { field ->
        !isHttpMessage(field) && hasWord(componentLabel(field), "note")
    }?.let { return it }

    val notesRoot = revealNotesContainer(tab) ?: return null
    val editors = walk(notesRoot).filterIsInstance<JTextComponent>().filter { !isHttpMessage(it) }.toList()
    return editors.filterIsInstance<JTextPane>().firstOrNull { it.isShowing }
        ?: editors.filterIsInstance<JTextPane>().firstOrNull()
        ?: editors.firstOrNull { it.isShowing }
        ?: editors.firstOrNull()
}

private fun revealNotesContainer(tab: Component): Component? {
    val pane = walk(tab).filterIsInstance<JTabbedPane>().firstOrNull { candidate ->
        indices(candidate).any { tabTitle(candidate, it).equals("Notes", ignoreCase = true) }
    }
    if (pane != null) {
        val index = indices(pane).first { tabTitle(pane, it).equals("Notes", ignoreCase = true) }
        pane.selectedIndex = index
        return pane.getComponentAt(index)
    }
    val button = walk(tab).filterIsInstance<AbstractButton>().firstOrNull { button ->
        hasWord(componentLabel(button), "notes") && !componentLabel(button).contains("send")
    } ?: return null
    button.doClick(0)
    return tab
}

private fun commitEditor(field: JTextComponent) {
    runCatching { field.caretPosition = field.document.length }
    val event = FocusEvent(field, FocusEvent.FOCUS_LOST, false)
    field.focusListeners.forEach { listener ->
        runCatching { listener.focusLost(event) }
    }
}

private fun editorTexts(root: Component): Set<String> {
    return walk(root).filterIsInstance<JTextComponent>()
        .map { it.text.orEmpty() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun diagnose(frame: Frame, tabName: String): String {
    val snapshot = runCatching {
        onEdt {
            val titles = walk(frame).filterIsInstance<JTabbedPane>().flatMap { pane ->
                indices(pane).map { tabTitle(pane, it) }
            }.filter { it.isNotBlank() }.distinct().take(24)
            val buttons = walk(frame).filterIsInstance<AbstractButton>()
                .map { componentLabel(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(16)
            "tabs=[${titles.joinToString()}] buttons=[${buttons.joinToString()}]"
        }
    }.getOrDefault("the Repeater window could not be read")
    return "Repeater Send was not available for tab '$tabName'. $snapshot"
}

private fun componentLabel(component: Component): String {
    val parts = mutableListOf<String?>()
    if (component is AbstractButton) {
        parts += component.text
        parts += component.actionCommand
    }
    if (component is JLabel) parts += component.text
    if (component is JComponent) {
        parts += runCatching { component.toolTipText }.getOrNull()
        parts += component.getClientProperty(JComponent.TOOL_TIP_TEXT_KEY) as? String
        parts += component.name
    }
    parts += runCatching { component.accessibleContext?.accessibleName }.getOrNull()
    return parts.filterNotNull()
        .joinToString(" ")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun hasWord(label: String, word: String): Boolean {
    val tokens = label.split(' ')
    if (word == "note") return tokens.any { it == "note" || it == "notes" }
    return tokens.any { it == word }
}

private fun isHttpMessage(field: JTextComponent): Boolean {
    val text = field.text.orEmpty()
    return looksLikeHttpRequest(text) || looksLikeHttpResponse(text)
}

private fun looksLikeHttpResponse(text: String): Boolean = text.trimStart().startsWith("HTTP/")

private fun looksLikeHttpRequest(text: String): Boolean {
    val start = text.trimStart()
    return start.startsWith("GET ") ||
        start.startsWith("POST ") ||
        start.startsWith("PUT ") ||
        start.startsWith("PATCH ") ||
        start.startsWith("DELETE ") ||
        start.startsWith("HEAD ") ||
        start.startsWith("OPTIONS ") ||
        start.startsWith("TRACE ") ||
        start.startsWith("CONNECT ")
}

private fun indices(pane: JTabbedPane) = 0 until pane.tabCount

private fun walk(root: Component?, descendInto: (Component) -> Boolean = { true }): Sequence<Component> = sequence {
    if (root == null) return@sequence
    val seen = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())
    val pending = ArrayDeque<Pair<Component, Int>>()
    pending.add(root to 0)
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeFirst()
        if (depth > 80 || !seen.add(node)) continue
        yield(node)
        if (node is Container && descendInto(node)) {
            val children = runCatching { node.components }.getOrNull() ?: continue
            for (child in children) {
                if (child != null) pending.add(child to depth + 1)
            }
        }
    }
}
