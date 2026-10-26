package net.portswigger.mcp.security

import burp.api.montoya.http.HttpService
import java.net.URI

internal data class RequestTemplate(val host: String, val service: HttpService, val raw: String) {
    fun requirePlaceholderCredentials() {
        val sensitive = setOf("authorization", "proxy-authorization", "cookie", "x-api-key")
        raw.lineSequence().takeWhile { it.isNotBlank() }.drop(1).forEach { line ->
            val name = line.substringBefore(':').trim().lowercase()
            if (name !in sensitive) return@forEach
            val value = line.substringAfter(':').trim()
            val handle = "\\{\\{[A-Za-z_][A-Za-z0-9_]*}}"
            val allowed = when (name) {
                "authorization", "proxy-authorization" -> value.matches(Regex("(?:Bearer|Basic) $handle", RegexOption.IGNORE_CASE))
                "cookie" -> value.matches(Regex("[A-Za-z0-9_-]+=$handle(?:; [A-Za-z0-9_-]+=$handle)*"))
                else -> value.matches(Regex(handle))
            }
            require(allowed) { "Credential headers must use stored variable handles only." }
        }
    }

    companion object {
        fun parse(template: String, rawUsesHttps: Boolean? = null): RequestTemplate =
            if (template.trimStart().startsWith("curl ")) fromCurl(template) else fromRaw(template, rawUsesHttps)

        private fun fromRaw(template: String, rawUsesHttps: Boolean?): RequestTemplate {
            val normalized = template.replace("\\r\\n", "\n").replace("\r\n", "\n")
            val first = normalized.lineSequence().firstOrNull()?.trim().orEmpty()
            require(first.matches(Regex("[A-Z]+ \\S+ HTTP/1\\.[01]"))) { "Expected a raw HTTP/1.x request." }
            val headerLines = normalized.lineSequence().drop(1).takeWhile { it.isNotBlank() }.toList()
            val hostLines = headerLines.filter { it.startsWith("Host:", true) }
            require(hostLines.size == 1 && headerLines.none { it.startsWith("Transfer-Encoding:", true) }) {
                "Raw request needs one Host header and no transfer encoding."
            }
            val lengthLines = headerLines.filter { it.substringBefore(':').trim().equals("Content-Length", true) }
            require(lengthLines.size <= 1 && lengthLines.all {
                it.substringAfter(':', "").trim().matches(Regex("[0-9]+"))
            }) { "Use one numeric Content-Length header or omit it." }
            val hostLine = hostLines.single()
            val authority = hostLine.substringAfter(':').trim()
            require(authority.isNotBlank() && !authority.endsWith(':')) {
                "Host header needs a hostname and, for a nonstandard port, a numeric port."
            }
            val target = first.split(' ')[1]
            val absolute = target.startsWith("http://", true) || target.startsWith("https://", true)
            val absoluteParts = if (absolute) Regex("^(https?)://([^/?#]+)([^#]*)$", RegexOption.IGNORE_CASE)
                .matchEntire(target) ?: throw IllegalArgumentException("Invalid raw request URL.") else null
            val scheme = absoluteParts?.groupValues?.get(1) ?: if (rawUsesHttps == false) "http" else "https"
            val uri = origin(scheme, absoluteParts?.groupValues?.get(2) ?: authority)
            if (absoluteParts != null) {
                val hostUri = origin(scheme, authority)
                require(uri.host.equals(hostUri.host, true) &&
                    effectivePort(uri) == effectivePort(hostUri)) { "Raw request URL and Host header differ." }
            }
            require(absolute || target.startsWith('/')) {
                "Raw request target must be a path or absolute HTTP URL."
            }
            val path = absoluteParts?.groupValues?.get(3)?.let {
                if (it.startsWith('?')) "/$it" else it.ifEmpty { "/" }
            } ?: target
            val raw = if (absolute) normalized.replaceRange(0, first.length, first.replace(target, path)) else template
            return RequestTemplate(
                VariableStore.normalizeHost(uri.host),
                HttpService.httpService(uri.host, effectivePort(uri), uri.scheme.equals("https", true)),
                raw
            )
        }

        private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

        private fun fromCurl(template: String): RequestTemplate {
            val args = tokenize(template)
            require(args.firstOrNull() == "curl") { "Expected curl command." }
            var url: String? = null
            var method: String? = null
            var body: String? = null
            val headers = mutableListOf<String>()
            var jsonBody = false
            var i = 1
            while (i < args.size) {
                when (val flag = args[i]) {
                    "-s", "-S", "-k", "-i", "-L", "--silent", "--show-error", "--insecure", "--include", "--location" -> Unit
                    "-X", "--request" -> method = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing curl method.")
                    "-H", "--header" -> headers += args.getOrNull(++i) ?: throw IllegalArgumentException("Missing curl header.")
                    "-b", "--cookie" -> headers += "Cookie: " + (args.getOrNull(++i) ?: throw IllegalArgumentException("Missing cookie."))
                    "-d", "--data", "--data-raw", "--data-binary", "--json" -> {
                        body = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing curl body.")
                        jsonBody = flag == "--json"
                    }
                    "--url" -> url = args.getOrNull(++i) ?: throw IllegalArgumentException("Missing curl URL.")
                    else -> if (flag.startsWith("http://") || flag.startsWith("https://")) url = flag
                        else throw IllegalArgumentException("Unsupported curl argument.")
                }
                i++
            }
            val supplied = url ?: throw IllegalArgumentException("Curl needs an HTTP URL.")
            require(!supplied.contains('\r') && !supplied.contains('\n') && !supplied.contains('#')) { "Invalid URL." }
            val match = Regex("^(https?)://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE).matchEntire(supplied)
                ?: throw IllegalArgumentException("Invalid HTTP URL.")
            val scheme = match.groupValues[1].lowercase()
            val uri = origin(scheme, match.groupValues[2])
            val target = match.groupValues[3].ifEmpty { "/" }.let { if (it.startsWith('?')) "/$it" else it }
            require(target.startsWith('/')) { "Invalid URL path." }
            val selectedMethod = (method ?: if (body == null) "GET" else "POST").uppercase()
            require(selectedMethod.matches(Regex("[A-Z]+"))) { "Invalid HTTP method." }
            require(headers.all { it.matches(Regex("[!#\u0024%&'*+.^_`|~0-9A-Za-z-]+: [^\\r\\n]*")) }) {
                "Invalid curl header."
            }
            require(headers.none { line ->
                val name = line.substringBefore(':').lowercase()
                name == "host" || name == "content-length" || name == "transfer-encoding"
            }) { "Curl cannot override routing or length headers." }
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            val hostHeader = uri.host + if (uri.port >= 0) ":${uri.port}" else ""
            val hasContentType = headers.any { it.startsWith("Content-Type:", true) }
            val raw = buildString {
                append("$selectedMethod $target HTTP/1.1\r\nHost: $hostHeader\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (jsonBody && !hasContentType) append("Content-Type: application/json\r\n")
                else if (body != null && !hasContentType) append("Content-Type: application/x-www-form-urlencoded\r\n")
                if (body != null) append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                append("\r\n")
                if (body != null) append(body)
            }
            return RequestTemplate(
                VariableStore.normalizeHost(uri.host), HttpService.httpService(uri.host, port, scheme == "https"), raw
            )
        }

        private fun origin(scheme: String, authority: String): URI {
            val uri = runCatching { URI("$scheme://$authority") }.getOrNull()
                ?: throw IllegalArgumentException("Invalid target host.")
            require(uri.host != null && uri.userInfo == null && uri.port <= 65535) { "Invalid target host." }
            return uri
        }

        private fun tokenize(command: String): List<String> {
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var active = false
            var index = 0
            while (index < command.length) {
                val char = command[index]
                when {
                    char == '\\' && quote != '\'' && index + 1 < command.length &&
                        (command[index + 1] == '\n' || command[index + 1] == '\r') -> {
                        index++
                        if (command[index] == '\r' && index + 1 < command.length && command[index + 1] == '\n') index++
                    }
                    char == '\\' && quote != '\'' && index + 1 < command.length -> {
                        token.append(command[++index]); active = true
                    }
                    quote != null && char == quote -> quote = null
                    quote == null && (char == '\'' || char == '"') -> { quote = char; active = true }
                    quote == null && char.isWhitespace() -> {
                        if (active) { result += token.toString(); token.clear(); active = false }
                    }
                    else -> { token.append(char); active = true }
                }
                index++
            }
            require(quote == null) { "Unclosed curl quote." }
            if (active) result += token.toString()
            return result
        }
    }
}
