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

## MCP activity log

The MCP tab has separate **Activity Log**, **Token Request**, **Auto-Approved HTTP Requests**, and **MCP Settings** views. Server Configuration and Advanced Settings are under MCP Settings. Activity Log shows the latest 200 tool calls, newest first. Open **Details** on a call to inspect its full arguments and result. **Clear logs** removes the retained calls; **Export logs** saves them as JSON Lines for debugging, oldest first. Session values and common credential fields are redacted in the view and export; the log stays in memory until cleared or the extension unloads.

See [COMPARE.md](COMPARE.md) to check the differences from PortSwigger's MCP server.
