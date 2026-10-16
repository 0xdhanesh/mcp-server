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

## Session variables and authorization replay

Use `acquire_session_var` to choose a token request by raw HTTP, curl, or Proxy history ID. It sends that request on demand, extracts a value from its response with JSONPath or regex, and stores the value without returning it to the model. For example, pass `{"name":"session","identity":"user","historyId":17,"host":"api.example","jsonPath":"$.access_token"}` to replay history request 17 and bind the resulting token to `api.example`. The `host` field can differ from the token endpoint host. A history ID keeps credentials in the original Burp request instead of MCP arguments.

In the MCP tab, **Token Request** saves a reusable request. Choose **Curl or raw HTTP** to paste a template, or **HTTP fields** to enter method, request URL, headers, and body parameters. For a raw request to an `http://` endpoint, select **HTTP** under **Raw HTTP transport**; raw requests otherwise use HTTPS. Curl uses the scheme in its URL and accepts multiline shell continuations. Replace credential values with handles such as `{"username":"{{username}}","password":"{{password}}"}` before saving. Under **Memory-only request values**, store each input handle for the desired identity and token request host; this entry is cleared after storing and never added to the saved template. Set the output variable name (for example `session`), token use host, and **JSONPath** with `$.access_token` for a JSON token response. Click **Save request**. MCP HTTP/1.1, HTTP/2, and `send_with_vars` sends return their original 401 by default. Set `allowTokenRequest=true` on a send to permit an internal token request and one retry; exactly one ready role is selected automatically, or a default role can be set in the MCP tab. `get_session_auth_status` and **Check 401 readiness** report setup without exposing values or sending HTTP. `send_with_vars` can infer the only role with all request handles; raw HTTP on a nonstandard HTTP port needs `usesHttps=false`. You can also call `acquire_configured_session_var` with `{"identity":"user"}` to acquire on demand. An opted-in retry invokes the saved token request internally, even if the MCP client does not request that endpoint directly. The template persists in Burp extension data, while input values and extracted tokens stay in memory. Standard `grant_type` values may be literal. Use **Clear saved request** to remove the template.

Use `extract_session_var` to capture a token from an existing response, or `set_session_var` to provide one explicitly. Variables belong to a role and host. `list_session_vars` returns only handles; `clear_session_vars` erases them. `send_with_vars` accepts a raw HTTP/1.x or curl template such as `curl https://api.example/users -H 'Authorization: Bearer {{session}}'` and an optional role name. `authz_replay` sends the same template for multiple roles, checks status/body/JSONPath assertions in the extension, compares the redacted response bodies, and adds redacted `[authz:<id>]` Organizer evidence.

See [ADR 0002](docs/adr/0002-variable-store.md) for the security rules and template limits.

See [USAGE.md](USAGE.md) for step-by-step curl, raw HTTP, and HTTP fields setup.

## MCP activity log

The MCP tab shows the latest 200 tool calls, newest first. Open **Details** on a call to inspect its full arguments and result. **Export logs** saves the retained calls as JSON Lines for debugging, oldest first. Session values and common credential fields are redacted in the view and export; the log stays in memory until the extension unloads.

See [COMPARE.md](COMPARE.md) to check the differences from PortSwigger's MCP server.
