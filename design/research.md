# Research — the Model Context Protocol, its official Java SDK, and Claude Code as a client

What the things we don't own actually do. About *them*, never us: a sentence starting "we chose"
is a `D_`. `## R_<slug> — <title>`, one claim per bullet, one provenance marker per claim —
**[docs]**, **[src]**, **[verified <date>, <version>]**, **[unverified]** (a hypothesis; a design
built on it says so). A claim is earned by its provenance, or by having cost real work to find
out. Checked against MCP specification 2025-03-26 and 2025-06-18, `io.modelcontextprotocol.sdk`
1.1.1 and Claude Code 2.1.281; a version-sensitive claim names the version it was seen on. Cite
by slug, `R_<slug>`, never by position; `grep '^## R_' design/research.md` is the index. The
first entry is the ruler: every later one trims to its length — which is how long this file
gets, so keep it short. When you have written an entry, re-read it against the one above and
cut what the provenance markers already carry.

---

## R_mcp_sdk_deps — io.modelcontextprotocol.sdk:mcp — runtime dependency tree

- `mcp:1.1.1` resolves to ~10 jars: `mcp-core`, `mcp-json-jackson3`, slf4j-api,
  `json-schema-validator`, `jackson-dataformat-yaml`, `snakeyaml-engine`, `com.ethlo.time:itu`.
  **[verified 2026-09-16, sdk 1.1.1, `gradle dependencies`]**
- It pulls **two Jackson lineages at once** — `com.fasterxml.jackson.core:jackson-annotations:2.20`
  (Jackson 2) and `tools.jackson.core:jackson-databind:3.0.3` (Jackson 3, on the new
  `tools.jackson` coordinates). A host already on Jackson 2 ends up with both.
  **[verified 2026-09-16, sdk 1.1.1]**
- `mcp-core` depends on `io.projectreactor:reactor-core:3.7.0` and `reactive-streams` — the
  SDK's async model is Reactor, not `CompletableFuture`. **[verified 2026-09-16, sdk 1.1.1]**
- GSON 2.13.2, for comparison, resolves to itself plus `error_prone_annotations:2.41.0`
  (annotations only, inert at runtime). **[verified 2026-09-16]**
- The SDK ships an `mcp-test` module that looks like the right harness for testing a
  third-party server; it is the SDK's own internal test tooling and is not published as a
  supported API. **[docs]**

## R_mcp_session_lifecycle — HTTP session management: what the spec requires of a server

- A client receiving **HTTP 404** on a request carrying an `Mcp-Session-Id` MUST start a new
  session — so 404 is the wire signal for "that id is stale", not a generic not-found. **[docs]**
- A server that requires a session id SHOULD answer a non-`initialize` request that omits
  `Mcp-Session-Id` with **HTTP 400** — a different condition from the above, and the client
  distinguishes them. **[docs]**
- `ping` is acceptable before initialization and carries no session, so it cannot be gated
  behind the session check. **[docs]**
- JSON-RPC reserves **-32000..-32099** for implementation-defined server errors, which is where
  session-state conditions belong; -32600 and -32602 describe malformed input instead. **[docs]**

## R_mcp_session_delete — HTTP DELETE: one named session, or an error

- A client done with a session SHOULD send DELETE carrying its `Mcp-Session-Id`, ending *that*
  session; a server MAY refuse with 405. Nothing ends more than one session — the text is the
  same in 2025-06-18 and 2025-11-25. **[docs]**
- A DELETE without the header falls under the general 400 for a request that omits it
  (`R_mcp_session_lifecycle`). **[docs]**
- A terminated session's id MUST get 404 on any later request, so a repeated DELETE is a 404,
  not a 200. **[docs]**
- The official SDK's servlet transport (`doDelete`, sdk 1.1.1) answers so: no header → 400 with
  -32601, an unknown id → 404, a live id → ended and 200. **[src]**

## R_mcp_stdio_framing — stdio transport: framing and the absence of sessions

- Framing is **newline-delimited JSON-RPC in UTF-8**, one message per line. The
  `Content-Length` header framing is LSP's, not MCP's. **[docs]**
- The stdio transport carries **no session id on the wire at all** — there is one implicit
  session for the life of the process. **[docs]**
- So a stdio client has no 404 to observe and no id to resend, and nothing in the protocol ever
  prompts it to re-initialize: a server-side session that dies under stdio is unrecoverable by
  the client.
  **[verified 2026-08-18, field incident]**

## R_mcp_iserror_tools_only — `isError` is a field on tool results only

- `isError` exists on `CallToolResult` and nowhere else; `ReadResourceResult` and
  `GetPromptResult` have no equivalent slot. **[docs]**
- The asymmetry is deliberate: a tool can meaningfully partially fail — it ran, produced a
  result, and that result is a description of the failure the model should read and act on.
  Resources and prompts are one-shot content producers. **[docs]**
- Consequence: a failing resource or prompt handler has only the JSON-RPC error envelope as a
  structured channel back to the client. **[docs]**

## R_mcp_empty_content — A tool result's `content` may be an empty array

- `CallToolResult.content` is required and typed `array`, with no `minItems`, so
  `"content": []` is a valid result. **[docs, schema 2025-03-26]**

## R_mcp_protocol_version_header — HTTP requests carry the negotiated protocol version

- From 2025-06-18 a Streamable HTTP client MUST send `MCP-Protocol-Version: <version>` on every
  request after `initialize`, and SHOULD send the version negotiated there. **[docs, spec 2025-06-18]**
- A server that receives none, and cannot tell the version otherwise, SHOULD assume
  `2025-03-26`. **[docs, spec 2025-06-18]**
- A server that receives an invalid or unsupported version MUST answer `400 Bad Request`.
  **[docs, spec 2025-06-18]**
- Claude Code sends the negotiated version, not its own latest: after negotiating `2025-11-25`
  every request carried `2025-11-25`, and `initialize` carried none.
  **[verified 2026-09-24, Claude Code 2.1.281, logging proxy]**
- Before `initialize` it probes with `server/discover` carrying `2026-07-28` and no session; an
  HTTP 400 makes it fall back to `initialize`. **[verified 2026-09-24, Claude Code 2.1.281]**
