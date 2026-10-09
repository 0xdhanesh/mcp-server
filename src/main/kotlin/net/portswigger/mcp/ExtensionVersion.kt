package net.portswigger.mcp

/**
 * Visible build label for this fork. Kept separate from the Gradle and BApp
 * version so the loaded extension can be identified in Burp.
 */
object ExtensionVersion {
    const val VERSION = "0.1"
    const val TAB_TITLE = "MCP v$VERSION"
    const val NAME = "Burp MCP Server v$VERSION"
}
