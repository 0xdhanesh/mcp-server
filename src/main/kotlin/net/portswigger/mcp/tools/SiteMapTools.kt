package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.sitemap.SiteMapFilter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.encodeHistoryItem
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.VariableStore
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable data class ListSitemap(val prefix: String? = null, val offset: Int = 0, val limit: Int = 20)
@Serializable data class GetSitemapItem(val id: String)
@Serializable internal data class SiteMapSummary(
    val id: String, val url: String, val method: String, val status: Int?,
    val requestLength: Int, val responseLength: Int?
)
@Serializable internal data class SiteMapPage(val items: List<SiteMapSummary>, val total: Int, val nextOffset: Int?)
@Serializable internal data class SiteMapDetail(val id: String, val request: String, val response: String?, val notes: String?)

/** Stateless content identity, keyed per registration so handles do not expose hashes of credentials.
 * No additional raw request/response cache; get resolves against Burp's current map.
 */
internal class SiteMapQueries(private val api: MontoyaApi, private val config: McpConfig, private val store: VariableStore) {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256")

    private fun permission() {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "Site map access denied by Burp Suite (HTTP history permission required)."
        }
    }

    private fun id(item: HttpRequestResponse): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        // Length-prefix fields to avoid delimiter collisions in arbitrary HTTP bodies.
        listOf(item.httpService().host(), item.httpService().port().toString(), item.httpService().secure().toString(),
            item.request().toString(), item.response()?.toString().orEmpty()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return "sm-" + mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    fun list(input: ListSitemap): String {
        require(input.offset >= 0 && input.limit in 1..100) { "offset must be nonnegative and limit must be 1..100." }
        input.prefix?.let(::registerScopeUrl)
        permission()
        val source = if (input.prefix == null) api.siteMap().requestResponses()
            else api.siteMap().requestResponses(SiteMapFilter.prefixFilter(input.prefix))
        val entries = source.map { id(it) to it }.distinctBy { it.first }.sortedBy { it.first }
        val selected = entries.drop(input.offset).take(input.limit).map { (handle, item) ->
            val request = item.request()
            val response = item.response()
            SiteMapSummary(handle, McpLogSanitizer.text(request.url(), store), request.method(),
                response?.statusCode()?.toInt(), request.toByteArray().length(), response?.toByteArray()?.length())
        }
        val next = (input.offset.toLong() + selected.size).takeIf { it < entries.size }?.toInt()
        return Json.encodeToString(SiteMapPage(selected, entries.size, next))
    }

    fun get(handle: String): String {
        require(handle.matches(Regex("sm-[0-9a-f]{64}"))) { "Invalid site-map handle; use list_sitemap." }
        permission()
        val item = api.siteMap().requestResponses().firstOrNull { id(it) == handle }
            ?: throw IllegalArgumentException("SITEMAP_ITEM_NOT_FOUND: Item changed, was removed, or belongs to a previous MCP server session; list again.")
        // Sanitize BEFORE history truncation, including credentials not yet in VariableStore.
        return encodeHistoryItem(SiteMapDetail(handle,
            McpLogSanitizer.text(item.request().toString(), store),
            item.response()?.toString()?.let { McpLogSanitizer.text(it, store) },
            item.annotations()?.notes()?.let { McpLogSanitizer.text(it, store) }))
    }
}

internal fun Server.registerSiteMapTools(api: MontoyaApi, config: McpConfig, store: VariableStore) {
    val queries = SiteMapQueries(api, config, store)
    mcpTool<ListSitemap>("List Burp Site Map HTTP summaries, optionally filtered by absolute HTTP(S) prefix. Uses HTTP-history data approval. limit 1..100; offset starts at 0; nextOffset null ends pagination. Order/handles are stable for unchanged content during this MCP server session; identical exchanges are deduplicated. Changes to the map can shift pages. No sends or body dumps.") {
        queries.list(this)
    }
    mcpTool<GetSitemapItem>("Fetch a redacted request/response pair using an id from list_sitemap. Uses HTTP-history approval and the history 5000-character JSON limit. Handles expire on content change/removal or server restart; list again. Does not send HTTP.") {
        queries.get(id)
    }
}
