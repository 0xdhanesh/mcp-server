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
import net.portswigger.mcp.security.TargetScopeSecurity
import java.util.regex.Pattern

private val summaryJson = Json { encodeDefaults = true }

internal fun openRepeaterTab(
    api: MontoyaApi,
    request: HttpRequest,
    requestedName: String?,
    httpMode: HttpMode,
    notes: String?
): String {
    requireNoStoredSecret(request.toString())
    val name = requestedName?.takeIf { it.isNotBlank() }?.let(::safeMetadata) ?: "mcp-${System.nanoTime().toString(16)}"
    val safeNotes = notes?.let(::safeMetadata)
    val frame = suiteFrame(api)
    RepeaterUi.preservingView(frame) {
        api.repeater().sendToRepeater(request, name)
    }
    val stored = RepeaterSession.get(name) ?: StoredRepeaterTab(name, request, httpMode)
    stored.request = request
    stored.httpMode = httpMode
    if (safeNotes != null) stored.notes = safeNotes
    stored.connectionId = stored.connectionId ?: RepeaterSession.connectionIdFor(name)
    RepeaterSession.save(stored)

    val notesWritten = if (safeNotes != null) {
        RepeaterUi.trySetNotes(frame, name, safeNotes, expectedRequest = request.toString())
    } else {
        false
    }

    return buildString {
        appendLine("Opened Repeater tab '$name'.")
        appendLine("Issue it with send_repeater_request so the model can read the response.")
        append("HTTP mode: ${httpMode.name}. Connection id: ${stored.connectionId}.")
        if (safeNotes != null) {
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
    requireNoStoredSecret(tab.request.toString())
    if (replacementContent.isNullOrBlank()) TargetScopeSecurity.check(tab.request, config)

    if (!replacementContent.isNullOrBlank()) {
        requireNoStoredSecret(replacementContent)
        val service = tab.request.httpService()
        tab.request = HttpRequest.httpRequest(service, normalizeHttpContent(replacementContent))
        TargetScopeSecurity.check(tab.request, config)
        if (httpModeName == null) tab.httpMode = HttpMode.HTTP_1
        val opened = suiteFrame(api)
        RepeaterUi.preservingView(opened) {
            api.repeater().sendToRepeater(tab.request, tab.name)
        }
    }
    if (httpModeName != null) tab.httpMode = parseHttpMode(httpModeName)
    if (notes != null) tab.notes = safeMetadata(notes)
    if (responseEndMarker != null) tab.responseEndMarker = responseEndMarker
    if (truncateAtEndMarker != null) tab.truncateAtEndMarker = truncateAtEndMarker

    val connection = connectionId?.takeIf { it.isNotBlank() }
        ?: tab.connectionId
        ?: RepeaterSession.connectionIdFor(tab.name)
    tab.connectionId = connection
    RepeaterSession.save(tab)

    val frame = suiteFrame(api)
    if (tab.notes.isNotEmpty()) {
        tab.notes = safeMetadata(tab.notes)
        RepeaterUi.trySetNotes(frame, tab.name, tab.notes, expectedRequest = tab.request.toString())
    }

    val explicitHttp = issueFrom?.trim()?.let { it.equals("http", true) || it.equals("api", true) } == true
    // Burp's UI destination can change independently of the editor text. Montoya cannot
    // verify that destination here, so enforced sends use the exact checked request via API.
    val scopeCheckedApi = config.enforceBurpTargetScope
    var sendMiss: String? = null
    TargetScopeSecurity.check(tab.request, config)
    val attempt = if (scopeCheckedApi) {
        RepeaterUiSend.NotAvailable("Scope enforcement uses the checked request destination through the HTTP API, not Repeater's independently editable UI destination.")
    } else {
        RepeaterUi.trySend(frame, tab.name, expectedRequest = tab.request.toString(), beforeSend = {
            // If enforcement was enabled while locating the UI, fail closed before clicking.
            if (config.enforceBurpTargetScope) throw net.portswigger.mcp.security.OutOfScopeException()
            TargetScopeSecurity.check(tab.request, config)
        })
    }
    when (val ui = attempt) {
        is RepeaterUiSend.Response -> {
            tab.lastResponse = ui.text
            RepeaterSession.save(tab)
            return repeaterResult(
                tab = tab,
                issuedBy = "Repeater tab ${ui.via}",
                detail = "The request was sent from Repeater tab '${tab.name}' using ${ui.via}. The response below is what that tab showed."
            )
        }

        is RepeaterUiSend.ClickedUnreadable -> {
            return ui.detail + "\nThe request was not sent a second time through the HTTP API."
        }

        is RepeaterUiSend.NotAvailable -> {
            if (!explicitHttp && !scopeCheckedApi) {
                return ui.detail +
                    "\nThe request stayed in Repeater tab '${tab.name}'. The Repeater response pane was not updated."
            }
            sendMiss = ui.detail
        }
    }

    val visibleRequest = RepeaterUi.tryReadRequest(frame, tab.name)
    if (!visibleRequest.isNullOrBlank()) {
        requireNoStoredSecret(visibleRequest)
        val service = tab.request.httpService()
        tab.request = HttpRequest.httpRequest(service, normalizeHttpContent(visibleRequest))
        RepeaterSession.save(tab)
    }

    val service = tab.request.httpService()
    TargetScopeSecurity.check(tab.request, config)
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
    TargetScopeSecurity.check(tab.request, config)
    val response = api.http().sendRequest(tab.request, tab.httpMode, connection)
    val raw = response?.toString() ?: "<no response>"
    tab.lastResponse = raw
    RepeaterSession.save(tab)

    val why = sendMiss +
        "\nHttp.sendRequest was used on connection '$connection'. That response is not shown in the Repeater response pane."
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
        applyNotes(item, safeMetadata(notes), append, color)
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
    annotations.setNotes(safeMetadata(next))
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
