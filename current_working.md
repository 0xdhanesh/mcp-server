# Current Testing and Feature Assessment

_Assessment date: 22 October 2026; release notes updated 23 October 2026_

## Current assessment

The project has a strong automated-test foundation: the saved Gradle report contains **174 tests across 20 test classes, with 0 failures, errors, or skips**. The report was generated after the latest source modifications.

A fresh run could not be completed in the assessment environment because Gradle could not reach the Maven repositories. This was a dependency-resolution/network failure, not a test failure. The existing report is available at `build/reports/tests/test/index.html`.

## What is already automated

| Area | Current automated coverage |
| --- | --- |
| MCP transport | Embedded Ktor server connection, ping, tool listing, and SSE client |
| Stdio proxy | Starts the bundled proxy process, lists tools, and invokes `url_encode` |
| HTTP | HTTP/1 normalization, HTTP/2 construction and pseudo-header ordering, and no-response handling |
| Session authentication | Token extraction, saved token requests, role selection, host binding, and one-time 401 retry |
| Authorization replay | Multi-role requests, assertions, and redacted Organizer evidence |
| Secret protection | Credential filtering, output/activity redaction, encoded-secret redaction, and identity and host isolation |
| Request approval | Exact hosts, ports, wildcards, and malformed or injected targets |
| Repeater | Creation, sending, notes, response markers, and focus restoration |
| Logger | Listing, commenting, sorting, ambiguity protection, and hidden/minimized UI states |
| Configuration | Persistence, target lists, configuration-editing restrictions, and Intercept/task-engine controls |
| Utilities | URL/Base64 encoding and decoding and random strings |
| Professional tools | Mocked Collaborator payloads/interactions and edition-dependent registration |
| UI structure | MCP tabs, scroll panes, activity clearing, and token-request form persistence |
| CI hygiene | Secret/unwanted-file checks, unit tests, JAR build, and release upload |

The main test sources are `src/test/kotlin/net/portswigger/mcp/tools/ToolsKtTest.kt`, `src/test/kotlin/net/portswigger/mcp/tools/SessionToolsTest.kt`, and `.github/workflows/ci.yml`.

## Tests worth automating next

### 1. Release consistency test

Compare `gradle.properties`, `ExtensionVersion.kt`, `BappManifest.bmf`, README, USAGE, and the generated JAR name. These were synchronized to `3.0.0` in the current development phase; verify the generated JAR after the full build.

### 2. Complete tool-catalog contract test

Assert the exact registered tool set and schemas: currently 52 documented tools, comprising 48 Community tools and four Professional-only tools. The existing integration test checks only a small subset. This would also detect that `COMPARE.md` still describes 43 tools.

### 3. Ktor security integration tests

Send real requests with malicious `Origin`, `Host`, browser `User-Agent`, and `Referer` values and verify the DNS-rebinding protections and security headers in `KtorServerManager.kt`.

### 4. Local target end-to-end tests

Start a disposable HTTP server and verify:

- HTTP/1 and HTTP/2 sends.
- 401 to token acquisition to single retry.
- JSONPath and header-regex extraction.
- Content-Length recalculation.
- Redaction of reflected credentials.
- Failed or missing token endpoints.

### 5. History and serialization coverage

Add focused tests for WebSocket history, Organizer filtering, Scanner issue serialization, regex pagination, invalid regular expressions, and maximum-result boundaries.

### 6. Lifecycle and concurrency tests

Exercise extension unload, server restart, port conflicts, concurrent tool calls, clearing secrets during a request, and the 200-entry activity limit under concurrency.

### 7. Packaging test

Run `embedProxyJar`, inspect the resulting archive, and verify that it contains the extension entry point and bundled proxy JAR.

Real Burp UI behavior, Professional Scanner/Collaborator traffic, HTTP/2 against actual targets, Claude Desktop installation, and third-party extension commands should remain hybrid tests: automate what can be mocked, but require a final run in Burp.

## Features usable now

Use `build/libs/burp-mcp-all-v3.0.0.jar` for the next manual test. The full build passed with 177 tests after disabling JUnit temp cleanup for this Windows run. Manual verification of this phase is pending.

After loading the JAR under **Burp > Extensions**, the MCP server is enabled by default at `127.0.0.1:9876`. Direct SSE clients can connect to that address; stdio-only clients use the bundled proxy. Claude Desktop installation is supported through the extension.

Useful workflows now include:

- Review Proxy, Logger, WebSocket, Organizer, and captured-request traffic.
- Send HTTP/1 or HTTP/2 requests and inspect responses.
- Move requests into Repeater or Intruder.
- Send and annotate requests in the MCP Repeater group while preserving the user's current view. Foreground sends use the Burp HTTP API and return their response to MCP.
- Add Proxy notes/highlights and Logger comments.
- Use memory-only, role-bound credentials and tokens.
- Replay the same request as `user`, `admin`, or other roles with `authz_replay`.
- Automatically acquire a token after a 401 only when `allowTokenRequest=true`.
- Inspect redacted MCP input/output through the Activity Log.
- Use Scanner and Collaborator features when running Burp Professional.

HTTP sends and sensitive-history reads require approval by default, while configuration-changing tools are disabled by default. These are appropriate safeguards for the current development stage.

## Current limitations and cautions

- The live sanity results in `tools.md` were recorded against version `1.3.8`, so they are not full manual certification of the current 3.0.0 build.
- Version metadata was synchronized to `3.0.0`; the JAR contains the extension entry point and embedded proxy.
- `COMPARE.md` describes 43 tools, while the current tool catalog contains 52.
- The 177-test run and versioned JAR build passed offline on this Windows workspace; Burp UI verification remains pending.
