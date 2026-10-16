<p align="center">
  <img src="assets/Banner-for-READMEmd.png" alt="Burp Suite MCP Server banner">
</p>

<p align="center">
  <a href="https://github.com/0xdhanesh/mcp-server/stargazers"><img src="https://img.shields.io/github/stars/0xdhanesh/mcp-server?label=stars" alt="GitHub stars"></a>
  <a href="https://github.com/0xdhanesh/mcp-server/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/0xdhanesh/mcp-server/ci.yml?branch=dev%2Ftool-enhancement2&amp;event=push&amp;label=tests" alt="Test status"></a>
  <a href="https://github.com/0xdhanesh/mcp-server/releases/latest"><img src="https://img.shields.io/badge/version-v2.0.5-blue" alt="Version: v2.0.5"></a>
</p>

# Burp Suite MCP Server

Burp Suite MCP Server lets AI assistants work with Burp Suite during web application testing.

## Features

- Connect Burp Suite to MCP clients, including Claude Desktop.
- Send HTTP requests and inspect server responses.
- Open requests in Repeater, resend them, and keep notes beside the results.
- Send requests to Intruder for further testing.
- Review and search Proxy traffic, WebSocket messages, captured requests, Logger entries, and Organizer items.
- Add comments, notes, and highlights to recorded traffic.
- Read and change Burp settings, Proxy Intercept, the task engine, and the active message editor.
- Encode and decode URLs and Base64 text, and generate random strings.
- Discover and use commands offered by compatible Burp extensions.
- In Burp Suite Professional, review Scanner issues and use Collaborator for out-of-band testing.
- Inspect MCP tool inputs and outputs in Burp Suite and export the activity log.

See [tools.md](tools.md) for the full feature list.
See [USAGE.md](USAGE.md) for step-by-step curl, raw HTTP, and HTTP fields setup.

## MCP views and activity log

The MCP tab has separate **Activity Log**, **Token Request**, **Auto-Approved HTTP Requests**, and **MCP Settings** views. A divider and gear icon distinguish MCP Settings, which contains Server Configuration and Advanced Settings.

In **Token Request**, memory-only field hints follow the `{{name}}` handles in the active request editor. **Dry Run check** validates the unsaved editor contents and reports handle coverage by role without saving or sending anything. **Check Automatic Readiness** checks the saved request and explains whether it can refresh a token after a 401. Automatic retry still requires an MCP HTTP send with `allowTokenRequest=true`.

Activity Log shows the latest 200 tool calls, newest first. Open **Details** on a call to inspect its full arguments and result. **Clear logs** removes the retained calls; **Export logs** saves them as JSON Lines for debugging, oldest first. Session values and common credential fields are redacted in the view and export; the log stays in memory until cleared or the extension unloads.

See [COMPARE.md](COMPARE.md) to check the differences from PortSwigger's MCP server.
