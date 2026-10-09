package net.portswigger.mcp.tools

import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
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
 * Burp 2026.9 names the Repeater Send control "repeaterSendButton". It is a
 * panel, not a JButton. The visible word "Send" is a child label, and the
 * tooltip is "Issue the request", so a search for a button titled Send misses
 * it. Older builds still use an icon button whose tooltip is Send. The Notes
 * editor is a text pane behind a Notes tab, not a titled border.
 */
internal sealed class RepeaterUiSend {
    data class Response(val text: String, val via: String = "Repeater Send") : RepeaterUiSend()
    data class ClickedUnreadable(val detail: String) : RepeaterUiSend()
    data class NotAvailable(val detail: String) : RepeaterUiSend()
}

internal object RepeaterUi {
    fun trySend(
        frame: Frame?,
        tabName: String,
        timeoutMs: Long = 15_000,
        expectedRequest: String? = null
    ): RepeaterUiSend {
        if (frame == null || runCatching { frame.isDisplayable }.getOrDefault(false) == false) {
            return RepeaterUiSend.NotAvailable("Burp's window is not available, so Repeater Send could not be used.")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var fired: FiredSend? = null
        while (fired == null && System.currentTimeMillis() < deadline) {
            val elapsed = timeoutMs - (deadline - System.currentTimeMillis())
            fired = runCatching {
                onEdt {
                    val tab = searchRoots(frame).firstNotNullOfOrNull { root ->
                        revealRepeaterTab(root, tabName, expectedRequest)
                    } ?: return@onEdt null
                    val control = findSendControl(tab, expectedRequest)
                    val editor = requestEditor(tab)
                    val shortcut = editor != null && hasSendShortcut(editor)
                    val ready = control?.takeIf { it.isEnabled && clickHasSize(it) }
                    if (ready == null && !(shortcut && (control == null || elapsed > 1_500))) {
                        return@onEdt null
                    }
                    val before = editorTexts(tab)
                    val via = if (ready != null) {
                        pressSend(ready)
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
    fun tryReadRequest(frame: Frame?, tabName: String, expectedRequest: String? = null): String? {
        if (frame == null) return null
        return runCatching {
            onEdt {
                val tab = revealRepeaterTab(frame, tabName, expectedRequest) ?: return@onEdt null
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

private fun searchRoots(frame: Frame): List<Component> {
    val others = Frame.getFrames().filter { it !== frame && it.isDisplayable }
    return listOf(frame) + others
}

private fun revealRepeaterTab(frame: Component, tabName: String, expectedRequest: String? = null): Component? {
    activateTool(frame, "Repeater")
    val suite = suiteContent(frame, "Repeater") ?: frame
    selectSubTab(suite, tabName)?.let { return it }
    // Current Repeater paints the request caption on its own tab strip, not as a suite JTabbedPane title.
    if (activateCaption(suite, tabName)) {
        selectSubTab(suite, tabName)?.let { return it }
        return suite
    }
    if (showingExpectedRequest(suite, expectedRequest)) return suite
    selectSubTab(frame, tabName)?.let { return it }
    if (activateCaption(frame, tabName)) return suiteContent(frame, "Repeater") ?: frame
    return null
}

private fun activateTool(frame: Component, title: String) {
    if (suiteContent(frame, title) != null) {
        selectSuiteTab(frame, title)
        return
    }
    if (findSendControl(frame, null)?.isShowing == true) return
    val button = walk(frame).filterIsInstance<AbstractButton>().firstOrNull { component ->
        component.isShowing && textEquals(component, title)
    } ?: return
    button.doClick(0)
}

private fun textEquals(component: Component, wanted: String): Boolean {
    val names = mutableListOf<String>()
    if (component is AbstractButton) names += component.text.orEmpty()
    if (component is JLabel) names += component.text.orEmpty()
    if (component is JComponent) names += component.name.orEmpty()
    return names.any { it.trim().equals(wanted, ignoreCase = true) }
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

private fun activateCaption(root: Component, tabName: String): Boolean {
    val header = walk(root).firstOrNull { component ->
        captionMatches(componentLabel(component), tabName) && !isPrimarySend(componentLabel(component))
    } ?: return false
    val button = when {
        header is AbstractButton -> header
        else -> generateSequence(header.parent) { it.parent }.filterIsInstance<AbstractButton>()
            .firstOrNull { !isPrimarySend(componentLabel(it)) }
    }
    if (button != null) {
        button.doClick(0)
    } else {
        val parent = header.parent
        val target = if (parent != null && parent.mouseListeners.isNotEmpty() && parent !is Frame) parent else header
        dispatchClick(target)
    }
    return true
}

private fun captionMatches(label: String, tabName: String): Boolean {
    val wanted = tabName.trim().lowercase()
    if (wanted.isEmpty() || label.isBlank()) return false
    if (label == wanted) return true
    // Tab strips truncate the caption and keep the full name only on the tooltip.
    if (wanted.startsWith(label) && label.length >= 8) return true
    if (label.length > wanted.length + 24) return false
    return label.contains(wanted)
}

private fun showingExpectedRequest(root: Component, expectedRequest: String?): Boolean {
    val needle = expectedRequest?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (needle.length < 8) return false
    return walk(root).any { component ->
        component.isShowing && componentText(component)?.contains(needle) == true
    }
}

private fun findSendControl(root: Component, expectedRequest: String?): Component? {
    val named = walk(root).filterIsInstance<JComponent>().filter { isNamedSend(it) }
    val buttons = walk(root).filterIsInstance<AbstractButton>().filter { isPrimarySend(componentLabel(it)) }
    val labeled = walk(root).filter { it is JLabel && it.text?.trim().equals("Send", ignoreCase = true) }
        .mapNotNull { clickableAround(it) }
    return (named + buttons + labeled)
        .distinct()
        .sortedWith(compareBy<Component>(
            { !it.isEnabled },
            { !it.isShowing },
            { !isNamedSend(it) },
            { !nearExpectedRequest(it, expectedRequest) }
        ))
        .firstOrNull()
}

private fun isNamedSend(component: Component): Boolean {
    val name = (component as? JComponent)?.name?.trim().orEmpty()
    if (!name.equals("repeaterSendButton", ignoreCase = true)) return false
    return !name.contains("option", ignoreCase = true)
}

private fun clickableAround(label: Component): Component? {
    var current: Component? = label
    var depth = 0
    while (current != null && depth < 4) {
        val name = (current as? JComponent)?.name.orEmpty()
        if (name.contains("option", ignoreCase = true)) return null
        if (current !== label && current.mouseListeners.isNotEmpty() && current !is Frame && current !is JTabbedPane) {
            return current
        }
        current = current.parent
        depth++
    }
    return null
}

private fun nearExpectedRequest(component: Component, expectedRequest: String?): Boolean {
    val needle = expectedRequest?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (needle.length < 8) return false
    return generateSequence(component) { it.parent }.take(8).any { ancestor ->
        walk(ancestor).any { child -> componentText(child)?.contains(needle) == true }
    }
}

private fun clickHasSize(component: Component): Boolean {
    if (component is AbstractButton) return true
    // A hidden tab keeps its Send panel in the tree. Click only the one on screen.
    return component.isShowing && component.width > 0 && component.height > 0
}

private fun pressSend(component: Component) {
    if (component is AbstractButton) {
        component.doClick(0)
        return
    }
    dispatchClick(component)
}

private fun dispatchClick(component: Component) {
    val width = component.width.takeIf { it > 1 } ?: component.preferredSize.width.coerceAtLeast(2)
    val height = component.height.takeIf { it > 1 } ?: component.preferredSize.height.coerceAtLeast(2)
    val x = width / 2
    val y = height / 2
    val whenMs = System.currentTimeMillis()
    component.dispatchEvent(
        MouseEvent(component, MouseEvent.MOUSE_PRESSED, whenMs, 0, x, y, 1, false, MouseEvent.BUTTON1)
    )
    component.dispatchEvent(
        MouseEvent(component, MouseEvent.MOUSE_RELEASED, whenMs, 0, x, y, 1, false, MouseEvent.BUTTON1)
    )
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
    return walk(root).mapNotNull { componentText(it) }.filter { it.isNotBlank() }.toSet()
}

/**
 * Burp 2026.9's request and response editors are panels named
 * httpRequestMessageAnalyser and httpResponseMessageAnalyser. Their raw
 * message is a public no-arg byte[] getter, not a [JTextComponent].
 */
private fun componentText(component: Component): String? {
    if (component is JTextComponent) return component.text
    val name = (component as? JComponent)?.name.orEmpty()
    if (!name.contains("MessageAnalyser", ignoreCase = true)) return null
    val getters = component.javaClass.methods.filter { method ->
        method.parameterCount == 0 && method.returnType == ByteArray::class.java
    }
    for (getter in getters) {
        val bytes = runCatching { getter.invoke(component) as? ByteArray }.getOrNull() ?: continue
        if (bytes.isEmpty()) continue
        val text = String(bytes, Charsets.ISO_8859_1)
        if (looksLikeHttpRequest(text) || looksLikeHttpResponse(text)) return text
    }
    return null
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
            val sendNames = walk(frame).filterIsInstance<JComponent>()
                .mapNotNull { it.name }
                .filter { it.contains("send", ignoreCase = true) }
                .distinct()
                .take(8)
            "tabs=[${titles.joinToString()}] buttons=[${buttons.joinToString()}] send=[${sendNames.joinToString()}]"
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
