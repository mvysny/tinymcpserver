# Claude Code's reconnect quirks against a single-session server

Found while measuring Claude Code 2.1.281 against a server restarted mid-session, with the
client's debug log (2026-09-24). A client's reconnect is the normal way back after the host
application restarts, so how the server meets it matters.

## Supersede churn on reconnect

After a restart, the client sends its old `Mcp-Session-Id`, gets a 404, and starts two
recoveries at once: the transport's reconnect and the tool call's retry, each with its own
`initialize`, 1–40 ms apart. A third `initialize` follows a second later, and the client keeps
that one. None of the three is DELETEd. This is reported upstream as anthropics/claude-code#96733.

Under an admission policy that supersedes (`D_supersede_sessions`), the two concurrent sessions
evict each other. When the tool call's retry loses, the model sees `MCP server "…" is not
connected` for a server that is up. The call after that one runs on the third session. What is
left for this library is whether it should help an embedder absorb the race until the client
fixes it.

- `Q_grace_window` — Would a short grace window help? The loser has not yet made a call. The
  idea: for N ms after a session's own `initialize`, a newcomer is admitted *beside* it instead
  of evicting it, and whichever one makes no call is left to idle eviction (`D_idle_eviction`).
  Does that reopen the two-clients problem that supersede exists to prevent, or is N ms too short
  for a second client to matter? And it is moot once #96733 is fixed, so is it worth the code?
- `Q_policy_inputs` — Where would the window live? Admission is the embedder's policy
  (`D_session_policy_injected`), but the policy sees only the live sessions, and `MCPSession`
  exposes neither when it was created nor whether it has served a call. An embedder can't write
  a grace window today. Should the session expose those two facts, or should the library ship the
  window as a ready-made policy?

## `server/discover`

Before each `initialize`, the client POSTs `server/discover` without a session header
(`R_mcp_protocol_version_header`). `HttpMCPServer` treats it like any other session-less request
that isn't `initialize`: it logs `Rejecting 'server/discover': no Mcp-Session-Id header` and
answers HTTP 400 "Server not initialized". The client then falls back to `initialize`.

- `Q_discover_spec` — Which specification revision or proposal defines `server/discover`, and what
  should a server that doesn't support it answer? Probably JSON-RPC "method not found", rather
  than the session error it gets now. The finding goes in `design/research.md`.
