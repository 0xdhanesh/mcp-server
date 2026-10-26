<p align="center">
  <img src="assets/Banner-for-READMEmd.png" alt="Burp Suite MCP Server banner">
</p>

<p align="center">
  <a href="https://github.com/0xdhanesh/mcp-server/stargazers"><img src="https://img.shields.io/github/stars/0xdhanesh/mcp-server?label=stars" alt="GitHub stars"></a>
  <a href="https://github.com/0xdhanesh/mcp-server/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/0xdhanesh/mcp-server/ci.yml?branch=dev%2Ftool-enhancement2&amp;event=push&amp;label=tests" alt="Test status"></a>
  <img src="https://img.shields.io/badge/local%20source-v3.0.6-blue" alt="Local source version: v3.0.6">
</p>

# Burp Suite MCP Server

Burp Suite MCP Server lets AI assistants work with Burp Suite during web application testing.

## Local connection security

The server listens on `127.0.0.1` only and requires an `X-Burp-Mcp-Key` header by default. In Burp's **MCP > Server Configuration**, click **Copy MCP access key** and add it to the local MCP client's SSE headers. For Claude Code, use `claude mcp add --transport sse -H "X-Burp-Mcp-Key: <copied key>" burp http://127.0.0.1:9876`, replacing the placeholder locally. Keep the key out of chat, reports, and repositories. Rotating the key disconnects existing clients until their header is updated. Keep HTTP request and project-data approval enabled for sensitive work.

Default extension discovery probes `X-Burp-Mcp-Discover: tools` and withholds raw response bodies. ATOR must be probed explicitly; its response body is withheld even when a caller requests it, because older ATOR builds can return live extracted tokens. Use Burp UI for sensitive ATOR exports.

Repeater sends now pass the MCP HTTP approval check before a UI click as well as before an HTTP API fallback. Common credential headers, bearer values, JWTs, and named secret fields are redacted from MCP tool output before delivery to clients. This pattern filter does not classify arbitrary customer data; use synthetic fixtures and avoid raw history readers when the client must not receive sensitive content. Host auto-approval covers every method and path on that host, so clear those entries for engagements that require per-request review.

## Features

- Connect Burp Suite to MCP clients, including Claude Desktop.
- Send HTTP requests and inspect server responses.
- Open MCP requests in a dedicated Repeater group, resend them, and keep notes beside the results.
- Send requests to Intruder for further testing.
- Review and search Proxy traffic, WebSocket messages, captured requests, Logger entries, and Organizer items.
- Add comments, notes, and highlights to recorded traffic.
- Read and change Burp settings, Proxy Intercept, the task engine, and the active message editor.
- Encode and decode URLs and Base64 text, and generate random strings.
- Discover and use commands offered by compatible Burp extensions.
- In Burp Suite Professional, review Scanner issues and use Collaborator for out-of-band testing.
- Inspect MCP tool inputs and outputs in Burp Suite and export the activity log.

See [tools.md](tools.md) for the full feature list.
See [USAGE.md](USAGE.md) for step-by-step curl and raw HTTP token request setup.

## MCP views and activity log

The MCP tab has separate **Activity Log**, **Token Request**, **Configured Token Requests and Roles**, **Auto-Approved HTTP Requests**, and **MCP Settings** views. A divider and gear icon distinguish MCP Settings, which contains Server Configuration and Advanced Settings.

In **Token Request**, choose CURL or raw HTTP and paste into the single request editor. The empty editor shows an example for the selected format, and the token use host field shows how to target a separate API host. **Dry Run check** validates the unsaved editor contents without saving or sending anything. **Configured Token Requests and Roles** shows the saved request, role bindings, and memory-only input fields. **Check Automatic Readiness** there explains whether it can refresh a token after a 401. Automatic retry still requires an MCP HTTP send with `allowTokenRequest=true`.

Activity Log shows the latest 200 tool calls, newest first. Open **Details** on a call to inspect its full arguments and result. **Clear logs** removes the retained calls; **Export logs** saves them as JSON Lines for debugging, oldest first. Session values and common credential fields are redacted in the view and export; the log stays in memory until cleared or the extension unloads.

MCP creates a visible **MCP** group in Repeater and places its tabs there. Manual Repeater tabs and other Burp tools remain available. If you are actively using Burp when MCP sends a grouped request, the extension uses Burp's HTTP API and returns the response to the MCP client without changing your selected tab; that response does not appear in Repeater's response pane. When Burp is in the background, MCP can use Repeater Send and read its response pane. Group assignment uses Burp's Swing menus because Montoya has no group API; check the tool result if your Burp version changes those menus.

See [COMPARE.md](COMPARE.md) to check the differences from PortSwigger's MCP server.
