package net.portswigger.mcp.tools

import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.message.requests.HttpRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Repeater tabs this extension opened.
 *
 * Montoya's [burp.api.montoya.repeater.Repeater] can display a request, but it
 * cannot list tabs, read them back, or store the tab note. This map is the
 * record the MCP tools use to issue that same request and to keep its note.
 */
internal class StoredRepeaterTab(
    val name: String,
    var request: HttpRequest,
    var httpMode: HttpMode,
    var notes: String = "",
    var connectionId: String? = null,
    var responseEndMarker: String? = null,
    var truncateAtEndMarker: Boolean = false,
    var lastResponse: String? = null
)

internal object RepeaterSession {
    private val tabs = ConcurrentHashMap<String, StoredRepeaterTab>()

    fun save(tab: StoredRepeaterTab) {
        tabs[tab.name] = tab
    }

    fun get(name: String): StoredRepeaterTab? = tabs[name]

    fun list(): List<StoredRepeaterTab> = tabs.values.sortedBy { it.name }

    fun connectionIdFor(name: String): String = "mcp-repeater-$name"

    fun resetForTests() {
        tabs.clear()
    }
}
