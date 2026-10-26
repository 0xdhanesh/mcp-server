package net.portswigger.mcp.config

object ConfigValidation {

    fun validateServerConfig(host: String, portText: String): String? {
        val trimmedHost = host.trim()
        val port = portText.trim().toIntOrNull()

        if (trimmedHost != "127.0.0.1") {
            return "MCP must listen on 127.0.0.1 only"
        }

        if (port == null) {
            return "Port must be a valid number"
        }

        if (port < 1024 || port > 65535) {
            return "Port is not within valid range"
        }

        return null
    }
}
