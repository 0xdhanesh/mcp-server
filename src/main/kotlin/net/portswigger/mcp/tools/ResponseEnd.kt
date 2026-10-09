package net.portswigger.mcp.tools

/**
 * Locates a caller-supplied end-of-response marker.
 *
 * The marker is a literal word or character sequence. A marker written with
 * the four characters `\r\n` is also tried as a real CRLF, because MCP clients
 * often send line endings that way.
 */
internal data class ResponseEndHit(
    val found: Boolean,
    val markerText: String,
    val startIndex: Int,
    val endIndexExclusive: Int
)

internal fun findResponseEnd(response: String, marker: String): ResponseEndHit {
    val direct = response.indexOf(marker)
    if (direct >= 0) {
        return ResponseEndHit(true, marker, direct, direct + marker.length)
    }

    val unescaped = unescapeMarker(marker)
    if (unescaped != marker) {
        val index = response.indexOf(unescaped)
        if (index >= 0) {
            return ResponseEndHit(true, unescaped, index, index + unescaped.length)
        }
    }

    return ResponseEndHit(false, marker, -1, -1)
}

/**
 * Returns the response the model should read.
 *
 * When [marker] is null or blank the response is unchanged, so existing send
 * tools keep their current output. When a marker is found, its character
 * offsets are appended. [truncate] keeps only the bytes up to and including
 * the marker.
 */
internal fun presentResponse(response: String, marker: String?, truncate: Boolean): String {
    if (marker.isNullOrEmpty()) return response

    val hit = findResponseEnd(response, marker)
    if (!hit.found) {
        return response + "\n\nEnd marker ${quoteMarker(marker)} was not found. The full response is above."
    }

    val after = response.length - hit.endIndexExclusive
    val note = "End marker ${quoteMarker(hit.markerText)} found at character ${hit.startIndex} " +
        "through ${hit.endIndexExclusive - 1}. Characters after the marker: $after."
    val shown = if (truncate) response.substring(0, hit.endIndexExclusive) else response
    val truncation = if (truncate) " Response truncated at the marker." else ""
    return shown + "\n\n" + note + truncation
}

private fun unescapeMarker(marker: String): String = marker
    .replace("\\r\\n", "\r\n")
    .replace("\\n", "\n")
    .replace("\\r", "\r")
    .replace("\\t", "\t")

private fun quoteMarker(marker: String): String = "\"" + marker
    .replace("\\", "\\\\")
    .replace("\r", "\\r")
    .replace("\n", "\\n")
    .replace("\t", "\\t")
    .replace("\"", "\\\"") + "\""
