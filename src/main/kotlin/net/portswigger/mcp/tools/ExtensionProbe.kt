package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Frame
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JTable

/**
 * Extensions such as ATOR expose functions by spoofing the response to a
 * request that carries a command header. Montoya has no API that lists other
 * loaded extensions or the tools they publish, so discovery uses that header
 * and, when the window is open, the Extensions table.
 */
internal data class ExtensionCommand(
    val name: String,
    val header: String,
    val command: String
)

internal val KNOWN_EXTENSION_PROBES = listOf(
    ExtensionCommand("ATOR", "X-ATOR-Command", "status"),
    ExtensionCommand("MCP discovery", "X-Burp-Mcp-Discover", "tools")
)

internal fun probeExtension(
    api: MontoyaApi,
    header: String,
    command: String,
    body: String = "",
    hostname: String = "127.0.0.1",
    port: Int = 1,
    usesHttps: Boolean = false
): String {
    val content = buildString {
        append("GET /burp-mcp-extension-probe HTTP/1.1\r\n")
        append("Host: $hostname\r\n")
        append("$header: $command\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
        append("\r\n")
        append(body)
    }
    val service = HttpService.httpService(hostname, port, usesHttps)
    val request = HttpRequest.httpRequest(service, normalizeHttpContent(content))
    val raw = try {
        api.http().sendRequest(request)?.toString()
    } catch (e: Exception) {
        return "No extension answered $header: $command (${e.message ?: e.javaClass.simpleName})."
    }
    if (raw.isNullOrBlank() || raw == "<no response>") {
        return "No extension answered $header: $command."
    }
    return summarizeExtensionResponse(header, command, raw)
}

internal fun summarizeExtensionResponse(header: String, command: String, raw: String): String {
    val body = httpBody(raw)
    val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    if (parsed == null) {
        return "Response for $header: $command\n$raw"
    }

    val extension = parsed.string("extension") ?: parsed.string("name") ?: "extension"
    val commands = parsed.string("commands")
    val tools = (parsed["tools"] as? JsonArray)?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull element.toString()
        val name = obj.string("name") ?: return@mapNotNull null
        val enabled = (obj["enabled"] as? JsonPrimitive)?.contentOrNull
        if (enabled == null) name else "$name enabled=$enabled"
    }.orEmpty()

    return buildString {
        appendLine("Extension: $extension")
        appendLine("Probe: $header: $command")
        if (!commands.isNullOrBlank()) appendLine("Commands: $commands")
        if (tools.isNotEmpty()) {
            appendLine("Tools:")
            tools.forEach { appendLine("- $it") }
        }
        appendLine("Body:")
        append(body)
    }.trimEnd()
}

internal fun loadedExtensionNames(frame: Frame?): List<String> {
    if (frame == null) return emptyList()
    return runCatching {
        onEdt {
            val root = walkForExtensions(frame)
            root.flatMap { table ->
                val model = table.model ?: return@flatMap emptyList()
                if (model.columnCount == 0 || model.rowCount == 0) return@flatMap emptyList()
                val nameColumn = (0 until model.columnCount).firstOrNull { column ->
                    val name = model.getColumnName(column) ?: return@firstOrNull false
                    name.contains("name", ignoreCase = true) || name.contains("extension", ignoreCase = true)
                } ?: 0
                (0 until model.rowCount).mapNotNull { row ->
                    model.getValueAt(row, nameColumn)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.distinct()
        }
    }.getOrDefault(emptyList())
}

private fun walkForExtensions(frame: Frame): List<JTable> {
    val extensions = findExtensionsRoot(frame) ?: return emptyList()
    return walkComponents(extensions).filterIsInstance<JTable>().toList()
}

private fun findExtensionsRoot(frame: Frame): java.awt.Component? {
    val tabbed = walkComponents(frame).filterIsInstance<javax.swing.JTabbedPane>().firstOrNull { pane ->
        (0 until pane.tabCount).any { pane.getTitleAt(it).equals("Extensions", ignoreCase = true) }
    } ?: return null
    val index = (0 until tabbed.tabCount).first { tabbed.getTitleAt(it).equals("Extensions", ignoreCase = true) }
    return tabbed.getComponentAt(index)
}

private fun walkComponents(root: java.awt.Component): Sequence<java.awt.Component> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<java.awt.Component, Boolean>())
    val pending = ArrayDeque<Pair<java.awt.Component, Int>>()
    pending.add(root to 0)
    while (pending.isNotEmpty()) {
        val (node, depth) = pending.removeFirst()
        if (depth > 30 || !seen.add(node)) continue
        yield(node)
        if (node is java.awt.Container) {
            val children = runCatching { node.components }.getOrNull() ?: continue
            for (child in children) {
                if (child != null) pending.add(child to depth + 1)
            }
        }
    }
}

private fun httpBody(raw: String): String {
    val crlf = raw.indexOf("\r\n\r\n")
    if (crlf >= 0) return raw.substring(crlf + 4)
    val lf = raw.indexOf("\n\n")
    if (lf >= 0) return raw.substring(lf + 2)
    return raw
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
