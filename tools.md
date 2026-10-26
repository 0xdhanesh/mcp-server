# Burp MCP server tools

This catalog lists the 52 tools registered by the current v3.0.0 source: 48 available in Burp Community and four available only in Burp Professional. **Tool name to invoke** is the name exposed to an MCP client. Tool arguments are defined by each tool's MCP input schema.

The **Sanity (v3.0.0)** results were checked on 23 October 2026 with the loaded extension in Burp Suite Community Edition v2026.9.2. All 48 exposed Community tools were invoked: **41 Pass**, **3 Partial**, and **4 Limited**. The four Professional-only tools were unavailable in Community. A disposable HTTP/1.1 and WebSocket target at `127.0.0.1:18080` supplied test traffic; Proxy checks used Burp's listener at `127.0.0.1:8080`. **Pass** means the stated behavior was observed, **Partial** means only part of it worked, and **Limited** means a required profile, setting, or compatible target was unavailable. These checks do not cover every argument or error path.

By default, Burp asks for approval before MCP sends HTTP requests or reads sensitive history. Project and user configuration editing is disabled until enabled in the MCP tab. Session values are held in memory, bound to an identity and host, and redacted from tool results. See [USAGE.md](USAGE.md) for token request setup and automatic 401 retry.

## Session variables and authorization replay

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| Set session variable | `set_session_var` | Store a value for one identity and host; return its handle. | Pass (two role-bound handles) |
| Extract session variable | `extract_session_var` | Capture a value from an existing response by last send, Proxy history ID, or Organizer ID. | Pass (JSONPath extraction) |
| Acquire session variable | `acquire_session_var` | Send a raw HTTP/curl request or replay a Proxy history request by ID; extract a response value with JSONPath or regex and store it under an identity and target host. Using a history ID keeps login credentials out of MCP arguments. | Pass (local token response) |
| Acquire configured session variable | `acquire_configured_session_var` | Run the token request saved in the MCP tab for one identity and store its extracted value; returns only the variable handle. | Limited (no saved token request) |
| Get session auth status | `get_session_auth_status` | Check the saved token request, required handles, and selected role without sending HTTP or returning values. | Pass (reported no saved request) |
| List session variables | `list_session_vars` | List handles, identities, and hosts without values. | Pass (values hidden) |
| Clear session variables | `clear_session_vars` | Erase all values or those for one identity. | Pass (test values cleared) |
| Send with variables | `send_with_vars` | Send a raw HTTP/1.x or curl template with `{{name}}` handles; return a redacted response. Omit `identity` only when one role has all required handles. For raw HTTP on a nonstandard port, set `usesHttps` explicitly. Set `allowTokenRequest=true` to refresh the selected role after a 401 and retry once. | Pass (role-bound HTTP 403) |
| Authorization replay | `authz_replay` | Send one request template as two to eight roles, evaluate response assertions, and save redacted evidence in Organizer. Use a curl URL with an explicit scheme for HTTP on a nonstandard port. | Pass (HTTP 403/200; evidence saved) |

## HTTP requests, Repeater, and Intruder

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| Send HTTP/1.1 request | `send_http1_request` | Send an HTTP/1.1 request. Returns the original 401 by default; set `allowTokenRequest=true` to acquire a token for the default or only ready role and retry once. Use `send_with_vars` for `{{name}}` placeholders. | Pass (HTTP 200) |
| Send HTTP/2 request | `send_http2_request` | Send an HTTP/2 request. Returns the original 401 by default; set `allowTokenRequest=true` to acquire a token for the default or only ready role and retry once. Use `send_with_vars` for `{{name}}` placeholders. | Limited (HTTP/1.1 target; no response) |
| Create HTTP/1.1 Repeater tab | `create_repeater_tab` | Open an HTTP/1.1 request in the visible MCP Repeater group without sending it. | Pass (tab created) |
| Create HTTP/2 Repeater tab | `create_repeater_tab_http2` | Open an HTTP/2 request in the visible MCP Repeater group without sending it. | Pass (tab created) |
| Send Repeater request | `send_repeater_request` | Send a grouped request and return its response. When Burp is in active use, the Burp HTTP API sends it without changing the selected tab; that response does not fill the Repeater response pane. | Pass (HTTP 200; API send) |
| Get Repeater tab | `get_repeater_tab` | Read a tab's request, response, and notes. | Pass (request, notes, response) |
| List Repeater tabs | `list_repeater_tabs` | List Repeater tabs created through MCP. | Pass (test tabs listed) |
| Set Repeater notes | `set_repeater_notes` | Add or replace notes in a Repeater tab. | Partial (session saved; UI panel not updated) |
| Set Repeater response end marker | `set_repeater_response_end_marker` | Set or clear where a Repeater response display ends. | Pass (END truncation) |
| Send proxy history item to Repeater | `send_proxy_history_to_repeater` | Open a Proxy history request by `historyId` in the MCP group; set `sendNow=true` to send it. | Pass (HTTP 200) |
| Send recorded request to Repeater | `send_recorded_request_to_repeater` | Open and resend a Proxy history request or a request captured since extension load. Use the ID from the matching source, not Burp Logger's visible `#`. | Pass (Proxy and captured; HTTP 200) |
| Send to Intruder | `send_to_intruder` | Open a request in Intruder. | Pass (tab verified in Burp) |

## Proxy history, Logger, WebSocket history, and Organizer

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| Get proxy HTTP history | `get_proxy_http_history` | Browse recorded HTTP requests and responses. | Pass (local request/response) |
| Search proxy HTTP history | `get_proxy_http_history_regex` | Find recorded HTTP traffic by a search pattern. | Pass (local match) |
| Summarize proxy HTTP history | `get_proxy_http_history_summary` | Read short summaries of recorded HTTP traffic. | Pass (ID, status, notes) |
| Get proxy HTTP history item | `get_proxy_http_history_item` | Read one recorded HTTP request and response. | Pass (item by ID) |
| List captured HTTP requests | `list_captured_http_requests` | List recent requests captured since the extension loaded, with capture IDs for `send_recorded_request_to_repeater`. | Pass (capture IDs listed) |
| List Logger requests | `list_logger_requests` | Browse Burp Logger's current filtered view, including visible Logger `#`, source tool, and comments. | Pass (local Logger rows) |
| Set Logger comment | `set_logger_comment` | Add or replace a comment on a visible Logger row using its Logger `#` and matching `sourceTool`. | Pass (comment read back) |
| Set proxy history notes | `set_proxy_history_notes` | Add notes or a highlight to a Proxy history item. | Pass (note read back) |
| Get proxy WebSocket history | `get_proxy_websocket_history` | Browse recorded WebSocket messages. | Pass (client message and echo) |
| Search proxy WebSocket history | `get_proxy_websocket_history_regex` | Find recorded WebSocket messages by a search pattern. | Pass (both messages matched) |
| Get Organizer items | `get_organizer_items` | Browse items saved in Organizer. | Pass (two replay evidence items) |
| Search Organizer items | `get_organizer_items_regex` | Find Organizer items by a search pattern. | Pass (both evidence items matched) |

## Burp configuration and controls

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| Output project options | `output_project_options` | Read Burp project settings. | Pass (JSON returned) |
| Output user options | `output_user_options` | Read Burp user settings. | Pass (JSON returned) |
| Set project options | `set_project_options` | Merge JSON into Burp project settings when configuration editing is enabled. | Limited (configuration editing disabled) |
| Set user options | `set_user_options` | Merge JSON into Burp user settings when configuration editing is enabled. | Limited (configuration editing disabled) |
| Set task execution engine state | `set_task_execution_engine_state` | Pause or resume Burp tasks. | Pass (paused and resumed) |
| Set Proxy Intercept state | `set_proxy_intercept_state` | Turn Proxy Intercept on or off. | Pass (on and off; restored) |
| Get active editor contents | `get_active_editor_contents` | Read the active message editor. | Pass (focused Repeater editor) |
| Set active editor contents | `set_active_editor_contents` | Replace text in the active message editor. | Pass (exact readback) |

## Utilities

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| URL encode | `url_encode` | URL-encode text. | Pass (a b&c to a+b%26c) |
| URL decode | `url_decode` | URL-decode text. | Pass (a%20b%26c to a b&c) |
| Base64 encode | `base64_encode` | Base64-encode text. | Pass (Burp MCP encoded) |
| Base64 decode | `base64_decode` | Base64-decode text. | Pass (Burp MCP decoded) |
| Generate random string | `generate_random_string` | Create a random string. | Pass (12 requested characters) |

## Burp Professional only

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| Get scanner issues | `get_scanner_issues` | Browse issues found by Burp Scanner. | Unavailable (Community Edition) |
| Generate Collaborator payload | `generate_collaborator_payload` | Create a Collaborator payload. Set `linkToCollaboratorTab=true` to use Burp's Collaborator tab; those interactions must be polled there. | Unavailable (Community Edition) |
| Get Collaborator interactions | `get_collaborator_interactions` | Poll the MCP Collaborator client for DNS, HTTP, or SMTP interactions; does not include payloads linked to Burp's Collaborator tab. | Unavailable (Community Edition) |
| Get Collaborator client | `get_collaborator_client` | Read the MCP Collaborator server address and secret key, or restore a client using `secretKey`. | Unavailable (Community Edition) |

## Other extension integration

| Name | Tool name to invoke | Description | Sanity (v3.0.0) |
| --- | --- | --- | --- |
| List extension tools | `list_extension_tools` | Probe loaded extensions for supported commands. Built-in probes cover ATOR and extensions that respond to `X-Burp-Mcp-Discover`; `header` and `command` can select another probe. | Partial (opaque rows; JSON not summarized) |
| Call extension command | `call_extension_command` | Send a discovered command to another extension through Burp's HTTP API. ATOR supports `status`, `refresh`, `export`, and `import`. | Partial (HTTP 200; JSON not summarized) |
