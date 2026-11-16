# Claude Code's reconnect quirks against a single-session server

Found while measuring Claude Code 2.1.281 against a server restarted mid-session, with the
client's debug log (2026-09-24). Re-checked on 2.1.325 (2026-11-16), the newest release then:
unchanged. A client's reconnect is the normal way back after the host application restarts, so
how the server meets it matters.

## Supersede churn on reconnect

After a restart, the client sends its old `Mcp-Session-Id`, gets a 404, and starts two
recoveries 1–5 ms apart: the transport's reconnect and the tool call's retry, each with its own
`initialize`. The two `initialize`s land about 40 ms apart. A third follows about 1.1 s later,
and the client keeps that one. None of the three is DELETEd. This is reported upstream as
anthropics/claude-code#96733, still open on 2026-11-16.

Under an admission policy that supersedes (`D_supersede_sessions`), the two concurrent sessions
evict each other. When the tool call's retry loses, the model sees `MCP server "…" is not
connected` for a server that is up. On 2.1.325 the retry's `initialize` went first. It was evicted
before its `notifications/initialized`, so the retried `tools/call` was never sent. The call after
that one runs on the third session. What is left for this library is whether it should help an
embedder absorb the race until the client fixes it.

The race is not only the server's doing. Under the default accept-all policy the reconnect path
drops its own session 1 ms after it connects ("The operation was aborted", "Terminal connection
error 1/3"). The retried call then succeeds on the retry's session, and the client opens the third
session anyway. Upstream comments on #96733 report one more consequence, on 2.1.286 (not
reproduced here): the retried `tools/call` executes, but the reconnect closes the connection
before the answer arrives, so the model repeats a non-idempotent call.

- `Q_grace_window` — Would a short grace window help? The loser has not yet made a call. The
  idea: for N ms after a session's own `initialize`, a newcomer is admitted *beside* it instead
  of evicting it, and whichever one makes no call is left to idle eviction (`D_idle_eviction`).
  The accept-all run on 2.1.325 is that window held open for good, and there the retried call
  succeeded. An N of a few hundred ms covers the ~40 ms pair and expires before the third
  `initialize` at ~1.1 s, which then supersedes both. Does that reopen the two-clients problem
  that supersede exists to prevent, or is N ms too short for a second client to matter? It
  would not help with the lost-answer double execution. And it is moot once #96733 is fixed, so
  is it worth the code?
- `Q_policy_inputs` — Where would the window live? Admission is the embedder's policy
  (`D_session_policy_injected`), but the policy sees only the live sessions, and `MCPSession`
  exposes neither when it was created nor whether it has served a call. An embedder can't write
  a grace window today. Should the session expose those two facts, or should the library ship the
  window as a ready-made policy?

## `server/discover`

Before each `initialize`, the client POSTs `server/discover` without a session header
(`R_mcp_protocol_version_header`). `HttpMCPServer` treats it like any other session-less request
that isn't `initialize`: it logs `Rejecting 'server/discover': no Mcp-Session-Id header` and
answers HTTP 400 "Server not initialized". The client then falls back to `initialize`. On 2.1.325
the probe carries `MCP-Protocol-Version: 2026-07-28`, and during the reconnect race two
probes go out 2 ms apart, one for each recovery.

- `Q_discover_spec` — Which specification revision or proposal defines `server/discover`, and what
  should a server that doesn't support it answer? Probably JSON-RPC "method not found", rather
  than the session error it gets now. The finding goes in `design/research.md`.
