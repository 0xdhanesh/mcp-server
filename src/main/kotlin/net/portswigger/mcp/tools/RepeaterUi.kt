package net.portswigger.mcp.tools

import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JButton
import javax.swing.JTabbedPane
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
 */
internal sealed class RepeaterUiSend {
    data class Response(val text: String) : RepeaterUiSend()
    data class ClickedUnreadable(val detail: String) : RepeaterUiSend()
    data object NotAvailable : RepeaterUiSend()
}

internal object RepeaterUi {
    fun trySend(frame: Frame?, tabName: String, timeoutMs: Long = 8_000): RepeaterUiSend {
        if (frame == null) return RepeaterUiSend.NotAvailable

        val before = runCatching { onEdt { visibleEditorTexts(frame, tabName) } }.getOrDefault(emptyList()).toSet()
        val clicked = runCatching {
            onEdt {
                val root = findSuiteTab(frame, "Repeater") ?: return@onEdt false
                if (!selectSubTab(root, tabName)) return@onEdt false
                val button = findSendButton(root) ?: return@onEdt false
                button.doClick()
                true
            }
        }.getOrDefault(false)

        if (!clicked) return RepeaterUiSend.NotAvailable

        val deadline = System.currentTimeMillis() + timeoutMs
        var latestHttp = ""
        var latest = ""
        while (System.currentTimeMillis() < deadline) {
            val texts = runCatching { onEdt { visibleEditorTexts(frame, tabName) } }.getOrDefault(emptyList())
            val fresh = texts.firstOrNull { looksLikeHttpResponse(it) && it !in before }
            if (fresh != null) return RepeaterUiSend.Response(fresh)
            latestHttp = texts.firstOrNull { looksLikeHttpResponse(it) }.orEmpty()
            latest = texts.maxByOrNull { it.length }.orEmpty()
            Thread.sleep(200)
        }

        if (latestHttp.isNotBlank()) return RepeaterUiSend.Response(latestHttp)
        return if (latest.isBlank()) {
            RepeaterUiSend.ClickedUnreadable("Send was clicked in Repeater tab '$tabName', but no response text was visible yet.")
        } else {
            RepeaterUiSend.ClickedUnreadable(
                "Send was clicked in Repeater tab '$tabName'. The response pane did not look like an HTTP response yet.\n$latest"
            )
        }
    }

    fun trySetNotes(frame: Frame?, tabName: String, notes: String): Boolean {
        if (frame == null) return false
        return runCatching {
            onEdt {
                val root = findSuiteTab(frame, "Repeater") ?: return@onEdt false
                if (!selectSubTab(root, tabName)) return@onEdt false
                val notesField = findNotesField(root) ?: return@onEdt false
                notesField.text = notes
                true
            }
        }.getOrDefault(false)
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
                val root = findSuiteTab(frame, "Repeater") ?: return@onEdt null
                if (!selectSubTab(root, tabName)) return@onEdt null
                walk(root).filterIsInstance<JTextComponent>()
                    .filter { it.isShowing }
                    .map { it.text.orEmpty() }
                    .firstOrNull { looksLikeHttpRequest(it) }
            }
        }.getOrNull()
    }

    fun suiteFrameOrNull(frameProvider: () -> Frame?): Frame? = runCatching { frameProvider() }.getOrNull()
}

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

private fun findSuiteTab(frame: Component, title: String): Component? {
    val tabbed = walk(frame).filterIsInstance<JTabbedPane>().firstOrNull { pane ->
        indices(pane).any { pane.getTitleAt(it).equals(title, ignoreCase = true) }
    } ?: return null
    val index = indices(tabbed).first { tabbed.getTitleAt(it).equals(title, ignoreCase = true) }
    tabbed.selectedIndex = index
    return tabbed.getComponentAt(index)
}

private fun selectSubTab(repeaterRoot: Component, tabName: String): Boolean {
    val tabbed = walk(repeaterRoot).filterIsInstance<JTabbedPane>().firstOrNull { pane ->
        indices(pane).any { (pane.getTitleAt(it) ?: "").contains(tabName) }
    } ?: return false
    val index = indices(tabbed).first { (tabbed.getTitleAt(it) ?: "").contains(tabName) }
    tabbed.selectedIndex = index
    return true
}

private fun findSendButton(repeaterRoot: Component): JButton? {
    return walk(repeaterRoot).filterIsInstance<JButton>()
        .filter { it.isShowing && it.isEnabled && isSendButton(it) }
        .minByOrNull { buttonSignature(it).length }
}

private fun isSendButton(button: JButton): Boolean {
    val signature = buttonSignature(button)
    if (signature.isBlank()) return false
    if (signature.contains("send group") || signature.contains("intruder") || signature.contains("comparer")) {
        return false
    }
    return signature == "send" || signature.startsWith("send ") || signature.contains(" send")
}

private fun buttonSignature(button: JButton): String {
    val accessible = runCatching { button.accessibleContext?.accessibleName }.getOrNull()
    return listOfNotNull(button.text, button.toolTipText, button.actionCommand, accessible)
        .joinToString(" ")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun findNotesField(repeaterRoot: Component): JTextComponent? {
    val titled = walk(repeaterRoot).firstOrNull { component ->
        val border = (component as? javax.swing.JComponent)?.border
        border is TitledBorder && border.title?.contains("Notes", ignoreCase = true) == true
    }
    if (titled != null) {
        walk(titled).filterIsInstance<JTextComponent>().firstOrNull()?.let { return it }
    }
    return walk(repeaterRoot).filterIsInstance<JTextComponent>().firstOrNull { field ->
        val accessible = runCatching { field.accessibleContext?.accessibleName }.getOrNull()
        accessible?.contains("note", ignoreCase = true) == true
    }
}

private fun visibleEditorTexts(frame: Frame, tabName: String): List<String> {
    val root = findSuiteTab(frame, "Repeater") ?: return emptyList()
    selectSubTab(root, tabName)
    return walk(root).filterIsInstance<JTextComponent>()
        .filter { it.isShowing }
        .map { it.text.orEmpty() }
        .filter { it.isNotBlank() }
        .toList()
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

private fun walk(root: Component?): Sequence<Component> = sequence {
    if (root == null) return@sequence
    val seen = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())
    val pending = ArrayDeque<Pair<Component, Int>>()
    pending.add(root to 0)
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeFirst()
        if (depth > 30 || !seen.add(node)) continue
        yield(node)
        if (node is Container) {
            val children = runCatching { node.components }.getOrNull() ?: continue
            for (child in children) {
                if (child != null) pending.add(child to depth + 1)
            }
        }
    }
}
