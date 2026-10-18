# Burp MCP server tools

This catalog lists the available tool groups. **Tool name to invoke** is the name exposed to an AI agent. The sanity results below record live checks with a locally loaded v1.3.8 extension on 14 October 2026, using `http://localhost:3000/` where a target was needed. **Pass** means the observed result matched the check; **Failed** means the attempted check did not produce the expected result. **Not validated** means the required UI state or Burp edition was unavailable, or the tool was not exercised. A failed check does not by itself establish a tool defect.

## Target scope and Site Map (MVP)

| Tool name to invoke | Arguments | Result / behavior |
| --- | --- | --- |
| `get_scope_status` | `url` | JSON `{ "inScope": true/false, "url": "..." }` from Burp Target scope. |
| `include_in_scope` | `url` | Includes an agreed URL/prefix; JSON `{ "ok": true, "url": "..." }`. Requires configuration editing enabled. |
| `exclude_from_scope` | `url` | Excludes a URL/prefix; same result and configuration-editing requirement. |
| `list_sitemap` | Optional `prefix`, `offset` (default 0), `limit` (default 20, range 1–100) | JSON `items`, `total`, `nextOffset`. Summaries contain `id`, URL, method, status, request/response byte lengths; no bodies. |
| `get_sitemap_item` | `id` from `list_sitemap` | Redacted request/response and notes, using the history 5,000-character JSON limit. |

Scope URLs and site-map prefixes must be absolute HTTP(S) URLs, including scheme and path; userinfo and fragments are rejected. Scope changes use Montoya's include/exclude semantics and are visible in Burp Target scope. They do not change MCP auto-approval. Enable **MCP Settings → Enable tools that can edit your config** to allow scope changes; an agent must not expand the agreed scope to work around a denial.

**Enforce Burp Target scope** is ON by default, including for existing installations. An empty scope blocks MCP sends. The shared gate checks each fully built request after variable expansion, independently of HTTP approval and auto-approved targets. Denials return an MCP error with text JSON `{ "code": "OUT_OF_SCOPE" }`; there is no approval retry. Include the token endpoint as well as the application endpoint when using token acquisition or automatic refresh. The gate covers HTTP/1.1, HTTP/2, Repeater UI/API sends (including history/capture replay), session acquisition, authorization replay, and automatic retries. Only the fixed HTTP extension-command probe at `127.0.0.1:1` is exempt; custom probe targets are gated. Turning enforcement OFF restores the prior scope behavior and leaves existing approvals in effect. This is a gate on this extension's sends, not a global fence on manual Burp traffic, other extensions, or attacks subsequently launched manually in Intruder.

With enforcement on, MCP Repeater sends use the HTTP API and the tab's stored destination, because Burp's independently editable UI destination cannot be verified through this API. The result explains that the Repeater response pane is not updated. Enforcement OFF retains the existing UI-send behavior. Existing templates still require a concrete routing host; this MVP does not add host-variable support.

Site-map reads share **HTTP history access** approval and do not send requests or change Scanner issue tools. `prefix` is passed to `SiteMapFilter.prefixFilter`. Items sort by opaque, session-local content handles; identical exchanges are deduplicated. For a static map, pass each `nextOffset` with the same prefix/limit until it is `null`: pages do not overlap or omit items. Changes to the live map can shift pages. A handle resolves unchanged request/response content during this MCP server session; changed/removed items and server restarts require listing again (`SITEMAP_ITEM_NOT_FOUND`). Common credential fields and stored session values are masked before detail truncation. A changed annotation alone does not invalidate a handle.

## Session variables and authorization replay

| Name | Tool name to invoke | Description |
| --- | --- | --- |
| Set session variable | `set_session_var` | Store a value for one identity and host; return its handle. |
| Extract session variable | `extract_session_var` | Capture a value from an existing response by last send, Proxy history ID, or Organizer ID. |
| Acquire session variable | `acquire_session_var` | Send a selected raw HTTP/curl or Proxy history request, extract a response value with JSONPath or regex, and store it under an identity and target host. |
| Acquire configured session variable | `acquire_configured_session_var` | Run the token request saved in the MCP tab for one identity and store its extracted value. |
| Get session auth status | `get_session_auth_status` | Check the saved token request, required handles, and selected role without sending HTTP or returning values. |
| List session variables | `list_session_vars` | List handles, identities, and hosts without values. |
| Clear session variables | `clear_session_vars` | Erase all values or those for one identity. |
| Send with variables | `send_with_vars` | Expand handles at send time and return a redacted response. Omit `identity` when one role has all required handles. Set `usesHttps=false` for raw HTTP on a nonstandard port. Set `allowTokenRequest=true` to refresh that role after a 401 and retry once. |
| Authorization replay | `authz_replay` | Send one template for multiple roles and evaluate response assertions in the extension. |

## HTTP requests, Repeater, and Intruder

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| Send HTTP/1.1 request | `send_http1_request` | Send an HTTP/1.1 request. Returns the original 401 by default; set `allowTokenRequest=true` to acquire a token for the default or only ready role and retry once. Use `send_with_vars` for `{{name}}` placeholders. | Pass (HTTP 200) |
| Send HTTP/2 request | `send_http2_request` | Send an HTTP/2 request. Returns the original 401 by default; set `allowTokenRequest=true` to acquire a token for the default or only ready role and retry once. Use `send_with_vars` for `{{name}}` placeholders. | Failed (no response from local target) |
| Create HTTP/1.1 Repeater tab | `create_repeater_tab` | Open an HTTP/1.1 request in Repeater. | Pass |
| Create HTTP/2 Repeater tab | `create_repeater_tab_http2` | Open an HTTP/2 request in Repeater. | Pass |
| Send Repeater request | `send_repeater_request` | Send a request from a Repeater tab and read its response. | Pass (HTTP/1.1; HTTP/2 target returned 400) |
| Get Repeater tab | `get_repeater_tab` | Read a tab's request, response, and notes. | Pass |
| List Repeater tabs | `list_repeater_tabs` | List Repeater tabs created through MCP. | Pass |
| Set Repeater notes | `set_repeater_notes` | Add or replace notes in a Repeater tab. | Pass |
| Set Repeater response end marker | `set_repeater_response_end_marker` | Set or clear where a Repeater response display ends. | Failed (check was cancelled) |
| Send proxy history item to Repeater | `send_proxy_history_to_repeater` | Open a Proxy history request in Repeater and optionally send it. | Pass (HTTP 200) |
| Send recorded request to Repeater | `send_recorded_request_to_repeater` | Open and resend a captured request or Proxy history request in Repeater. | Pass (both sources; HTTP 200) |
| Send to Intruder | `send_to_intruder` | Open a request in Intruder. | Pass (command accepted) |

## Proxy history, Logger, WebSocket history, and Organizer

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| Get proxy HTTP history | `get_proxy_http_history` | Browse recorded HTTP requests and responses. | Pass |
| Search proxy HTTP history | `get_proxy_http_history_regex` | Find recorded HTTP traffic by a search pattern. | Pass |
| Summarize proxy HTTP history | `get_proxy_http_history_summary` | Read short summaries of recorded HTTP traffic. | Pass |
| Get proxy HTTP history item | `get_proxy_http_history_item` | Read one recorded HTTP request and response. | Pass |
| List captured HTTP requests | `list_captured_http_requests` | List requests captured during the extension session. | Pass |
| List Logger requests | `list_logger_requests` | Browse Logger rows and their comments. | Pass |
| Set Logger comment | `set_logger_comment` | Add or update a Logger row's comment. | Pass |
| Set proxy history notes | `set_proxy_history_notes` | Add notes or a highlight to a Proxy history item. | Pass |
| Get proxy WebSocket history | `get_proxy_websocket_history` | Browse recorded WebSocket messages. | Pass |
| Search proxy WebSocket history | `get_proxy_websocket_history_regex` | Find recorded WebSocket messages by a search pattern. | Pass (no matching item) |
| Get Organizer items | `get_organizer_items` | Browse items saved in Organizer. | Pass (Organizer was empty) |
| Search Organizer items | `get_organizer_items_regex` | Find Organizer items by a search pattern. | Pass (Organizer was empty) |

## Burp configuration and controls

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| Output project options | `output_project_options` | Read Burp project settings. | Pass |
| Output user options | `output_user_options` | Read Burp user settings. | Pass |
| Set project options | `set_project_options` | Change Burp project settings. | Not validated (not exercised) |
| Set user options | `set_user_options` | Change Burp user settings. | Not validated (not exercised) |
| Set task execution engine state | `set_task_execution_engine_state` | Pause or resume Burp tasks. | Not validated (not exercised) |
| Set Proxy Intercept state | `set_proxy_intercept_state` | Turn Proxy Intercept on or off. | Not validated (not exercised) |
| Get active editor contents | `get_active_editor_contents` | Read the active message editor. | Not validated (no active editor) |
| Set active editor contents | `set_active_editor_contents` | Replace text in the active message editor. | Not validated (no active editor) |

## Utilities

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| URL encode | `url_encode` | URL-encode text. | Pass |
| URL decode | `url_decode` | URL-decode text. | Pass |
| Base64 encode | `base64_encode` | Base64-encode text. | Pass |
| Base64 decode | `base64_decode` | Base64-decode text. | Pass |
| Generate random string | `generate_random_string` | Create a random string. | Pass |

## Burp Professional only

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| Get scanner issues | `get_scanner_issues` | Browse issues found by Burp Scanner. | Not validated (Professional unavailable) |
| Generate Collaborator payload | `generate_collaborator_payload` | Create a Collaborator payload. | Not validated (Professional unavailable) |
| Get Collaborator interactions | `get_collaborator_interactions` | Check for Collaborator interactions. | Not validated (Professional unavailable) |
| Get Collaborator client | `get_collaborator_client` | Read Collaborator client details. | Not validated (Professional unavailable) |

## Other extension integration

| Name | Tool name to invoke | Description | Sanity |
| --- | --- | --- | --- |
| List extension tools | `list_extension_tools` | Discover commands offered by other Burp extensions. | Pass (ShotUp discovered) |
| Call extension command | `call_extension_command` | Run a command offered by another Burp extension. | Pass (ShotUp command ran) |

## Phase 2+ backlog (not implemented in this MVP)

- Scanner crawl/audit/poll/report (Professional), behind human approval and the scope gate.
- Cookie jar list/get/set and a VariableStore bridge, with redacted values or handles.
- Response variations / keywords analyzers alongside authorization replay.
- Replace blocking approval bridges with suspending gates in a separate guardrail pass.
- Batch sends, Comparer, Intruder result reads, match/replace, macros, and the separate Skills project remain outside this MVP.
