package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.HttpRequestSecurity
import java.util.regex.Pattern

private val summaryJson = Json { encodeDefaults = true }

internal fun openRepeaterTab(
    api: MontoyaApi,
    request: HttpRequest,
    requestedName: String?,
    httpMode: HttpMode,
    notes: String?
): String {
    val name = requestedName?.takeIf { it.isNotBlank() } ?: "mcp-${System.nanoTime().toString(16)}"
    api.repeater().sendToRepeater(request, name)
    val stored = RepeaterSession.get(name) ?: StoredRepeaterTab(name, request, httpMode)
    stored.request = request
    stored.httpMode = httpMode
    if (notes != null) stored.notes = notes
    stored.connectionId = stored.connectionId ?: RepeaterSession.connectionIdFor(name)
    RepeaterSession.save(stored)

    val frame = suiteFrame(api)
    val notesWritten = if (notes != null) RepeaterUi.trySetNotes(frame, name, notes) else false

    return buildString {
        appendLine("Opened Repeater tab '$name'.")
        appendLine("Issue it with send_repeater_request so the model can read the response.")
        append("HTTP mode: ${httpMode.name}. Connection id: ${stored.connectionId}.")
        if (notes != null) {
            append(" Notes saved")
            append(if (notesWritten) " in the Repeater Notes field." else " for this tab.")
        }
    }.trimEnd()
}

internal fun issueRepeaterTab(
    api: MontoyaApi,
    config: McpConfig,
    tabName: String,
    replacementContent: String?,
    httpModeName: String?,
    connectionId: String?,
    responseEndMarker: String?,
    truncateAtEndMarker: Boolean?,
    notes: String?,
    issueFrom: String?
): String {
    val tab = RepeaterSession.get(tabName)
        ?: return "No Repeater tab named '$tabName'. Send a request to Repeater first, and pass that tab name."

    if (!replacementContent.isNullOrBlank()) {
        val service = tab.request.httpService()
        tab.request = HttpRequest.httpRequest(service, normalizeHttpContent(replacementContent))
        if (httpModeName == null) tab.httpMode = HttpMode.HTTP_1
        api.repeater().sendToRepeater(tab.request, tab.name)
    }
    if (httpModeName != null) tab.httpMode = parseHttpMode(httpModeName)
    if (notes != null) tab.notes = notes
    if (responseEndMarker != null) tab.responseEndMarker = responseEndMarker
    if (truncateAtEndMarker != null) tab.truncateAtEndMarker = truncateAtEndMarker

    val connection = connectionId?.takeIf { it.isNotBlank() }
        ?: tab.connectionId
        ?: RepeaterSession.connectionIdFor(tab.name)
    tab.connectionId = connection
    RepeaterSession.save(tab)

    val frame = suiteFrame(api)
    if (tab.notes.isNotEmpty()) RepeaterUi.trySetNotes(frame, tab.name, tab.notes)

    val fromUi = (issueFrom ?: "repeater").let {
        it.equals("repeater", true) || it.equals("ui", true) || it.equals("repeater_tab", true)
    }

    if (fromUi) {
        when (val ui = RepeaterUi.trySend(frame, tab.name)) {
            is RepeaterUiSend.Response -> {
                tab.lastResponse = ui.text
                RepeaterSession.save(tab)
                return repeaterResult(
                    tab = tab,
                    issuedBy = "Repeater tab Send button",
                    detail = "The request was sent from Repeater tab '${tab.name}'. The response below is what that tab showed."
                )
            }

            is RepeaterUiSend.ClickedUnreadable -> {
                return ui.detail + "\nThe request was not sent a second time through the HTTP API."
            }

            RepeaterUiSend.NotAvailable -> Unit
        }
    }

    val visibleRequest = RepeaterUi.tryReadRequest(frame, tab.name)
    if (!visibleRequest.isNullOrBlank()) {
        val service = tab.request.httpService()
        tab.request = HttpRequest.httpRequest(service, normalizeHttpContent(visibleRequest))
        RepeaterSession.save(tab)
    }

    val service = tab.request.httpService()
    val allowed = runBlocking {
        HttpRequestSecurity.checkHttpRequestPermission(
            service.host(), service.port(), config, tab.request.toString(), api
        )
    }
    if (!allowed) {
        api.logging().logToOutput("MCP Repeater send denied: ${service.host()}:${service.port()}")
        return "Send HTTP request denied by Burp Suite"
    }

    api.logging().logToOutput(
        "MCP issuing Repeater tab '${tab.name}' via Http.sendRequest ${service.host()}:${service.port()} connection $connection"
    )
    val response = api.http().sendRequest(tab.request, tab.httpMode, connection)
    val raw = response?.toString() ?: "<no response>"
    tab.lastResponse = raw
    RepeaterSession.save(tab)

    val why = if (fromUi) {
        "Repeater has no Send method in the Montoya API, and the Send button was not available. " +
            "The request open in tab '${tab.name}' was issued with Http.sendRequest on connection '$connection'."
    } else {
        "Issued with Http.sendRequest on connection '$connection' for Repeater tab '${tab.name}'."
    }
    return repeaterResult(tab, "Burp HTTP API", why)
}

internal fun describeRepeaterTab(tabName: String): String {
    val tab = RepeaterSession.get(tabName) ?: return "No Repeater tab named '$tabName'."
    return buildString {
        appendLine("Repeater tab: ${tab.name}")
        appendLine("HTTP mode: ${tab.httpMode.name}")
        appendLine("Connection id: ${tab.connectionId ?: RepeaterSession.connectionIdFor(tab.name)}")
        appendLine("Notes: ${tab.notes.ifEmpty { "<none>" }}")
        appendLine("End marker: ${tab.responseEndMarker ?: "<none>"}")
        appendLine("Truncate at end marker: ${tab.truncateAtEndMarker}")
        appendLine("Request:")
        appendLine(tab.request.toString())
        appendLine("Last response:")
        append(
            presentResponse(
                tab.lastResponse ?: "<no response yet>",
                tab.responseEndMarker,
                tab.truncateAtEndMarker
            )
        )
    }.trimEnd()
}

internal fun listRepeaterTabs(): String {
    val tabs = RepeaterSession.list()
    if (tabs.isEmpty()) return "No Repeater tabs have been opened by MCP in this session."
    return tabs.joinToString("\n") { tab ->
        val marker = tab.responseEndMarker?.let { " endMarker=$it" }.orEmpty()
        "${tab.name} mode=${tab.httpMode.name} connection=${tab.connectionId ?: "-"} notes=${tab.notes.ifEmpty { "-" }}$marker"
    }
}

internal fun annotateProxyHistory(
    api: MontoyaApi,
    historyId: Int?,
    regex: String?,
    notes: String,
    highlightColor: String?,
    append: Boolean
): String {
    if (historyId == null && regex.isNullOrBlank()) {
        return "Provide historyId or regex so notes are not written onto every history item."
    }

    val compiled = regex?.takeIf { it.isNotBlank() }?.let { Pattern.compile(it) }
    val matched = if (compiled != null) {
        api.proxy().history { it.contains(compiled) }
    } else {
        api.proxy().history()
    }.filter { historyId == null || it.id() == historyId }

    if (matched.isEmpty()) return "No proxy history item matched."
    if (historyId == null && matched.size > 100) {
        return "Refusing to annotate ${matched.size} history items. Pass historyId or a narrower regex."
    }

    val color = highlightColor?.takeIf { it.isNotBlank() }?.let { parseHighlightColor(it) }
    val updated = matched.mapNotNull { item ->
        if (item.annotations() == null) return@mapNotNull null
        applyNotes(item, notes, append, color)
        item.id()
    }
    if (updated.isEmpty()) return "Matched history items have no notes field."
    return "Updated proxy history notes on ${updated.size} item(s): ${updated.joinToString(", ")}."
}

internal fun proxyHistorySummaryLine(item: ProxyHttpRequestResponse): String {
    val status = if (runCatching { item.hasResponse() }.getOrDefault(false)) {
        runCatching { item.response()?.statusCode()?.toInt() }.getOrNull()
    } else {
        null
    }
    val summary = ProxyHistorySummary(
        id = runCatching { item.id() }.getOrDefault(-1),
        time = runCatching { item.time()?.toString() }.getOrNull(),
        method = runCatching { item.request()?.method() }.getOrNull(),
        url = runCatching { item.request()?.url() }.getOrNull(),
        status = status,
        notes = runCatching { item.annotations()?.notes() }.getOrNull(),
        edited = runCatching { item.edited() }.getOrNull()
    )
    return summaryJson.encodeToString(ProxyHistorySummary.serializer(), summary)
}

internal fun findProxyHistoryItem(api: MontoyaApi, historyId: Int): ProxyHttpRequestResponse? {
    return api.proxy().history().firstOrNull { it.id() == historyId }
}

internal fun parseHttpMode(raw: String): HttpMode {
    return when (raw.trim().lowercase().replace("-", "").replace("/", "").replace("_", "").replace(" ", "")) {
        "http1", "http11", "11", "1", "h1" -> HttpMode.HTTP_1
        "http2", "h2", "2" -> HttpMode.HTTP_2
        "http2ignorealpn" -> HttpMode.HTTP_2_IGNORE_ALPN
        "auto" -> HttpMode.AUTO
        else -> HttpMode.valueOf(raw.trim().uppercase())
    }
}

internal fun parseHighlightColor(raw: String): HighlightColor {
    val trimmed = raw.trim()
    return HighlightColor.values().firstOrNull { color ->
        color.name.equals(trimmed, ignoreCase = true) || color.displayName().equals(trimmed, ignoreCase = true)
    } ?: throw IllegalArgumentException(
        "Unknown highlight color '$raw'. Use one of: ${HighlightColor.values().joinToString { it.name }}."
    )
}

@Serializable
internal data class ProxyHistorySummary(
    val id: Int,
    val time: String? = null,
    val method: String? = null,
    val url: String? = null,
    val status: Int? = null,
    val notes: String? = null,
    val edited: Boolean? = null
)

private fun applyNotes(
    item: ProxyHttpRequestResponse,
    notes: String,
    append: Boolean,
    color: HighlightColor?
) {
    val annotations = item.annotations() ?: return
    val existing = annotations.notes().orEmpty()
    val next = if (append && existing.isNotBlank()) "$existing\n$notes" else notes
    annotations.setNotes(next)
    if (color != null) annotations.setHighlightColor(color)
}

private fun repeaterResult(tab: StoredRepeaterTab, issuedBy: String, detail: String): String {
    return buildString {
        appendLine(detail)
        appendLine("Issued by: $issuedBy.")
        appendLine("HTTP mode: ${tab.httpMode.name}. Connection id: ${tab.connectionId}.")
        if (tab.notes.isNotEmpty()) appendLine("Notes: ${tab.notes}")
        appendLine()
        append(presentResponse(tab.lastResponse ?: "<no response>", tab.responseEndMarker, tab.truncateAtEndMarker))
    }.trimEnd()
}

private fun suiteFrame(api: MontoyaApi) = RepeaterUi.suiteFrameOrNull {
    api.userInterface().swingUtils().suiteFrame()
}
