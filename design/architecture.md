# Architecture

How the pieces compose — what no single symbol can say and what would be expensive to overturn:
wiring and dependency direction, the lifecycle / threading / data-flow story, the flows a newcomer
needs, where to start reading. **Normative: the code conforms.** Change this file first, then the
code. Not here: why (`decisions.md` — cite the `D_`), what the specification or the SDK does
(`research.md` — cite the `R_`), one symbol's behaviour (its doc comment), the package map
(`AGENTS.md`). Only the sections with content; the first bullet or step of each is the ruler.
Cap 12 KB — over it, research or doc-comment content has crept in.

---

## Wiring

- Dependencies point one way: a transport → `MCPHandler` → `MCPSession` → the tool / resource /
  prompt handlers. Nothing at or below `MCPHandler` knows which transport is driving it, or that
  transports exist at all (`D_handler_transport_split`).
- `MCPHandler` is both the dispatch core and the configuration object. A caller registers tools
  on it and sets its session listeners, then hands it to exactly one transport, which drives its
  `start()` / `stop()`.
- The two transports are shells over the same handler. `HttpMCPServer` adds the JDK `HttpServer`,
  method routing, `Mcp-Session-Id` validation and JSON-RPC framing over HTTP; `StdioMCPServer`
  adds a newline-delimited read loop and holds one implicit session.
- `client` depends on `MCPProtocol` and on nothing else in the server package; nothing in
  the server package depends on it.
- Errors travel up as exceptions: `HttpMCPServer.handleRequest` is the single place one becomes
  an HTTP response — the seam the throw/write invariant in `AGENTS.md` protects
  (`D_three_error_layers`).
- No static mutable state. `MCPSession.getCurrent()` is the one thread-local, and it exists so
  tool code can reach its session and, through it, the shared executor.

## Flows

**A tool call over HTTP** (on an `HttpServer` thread):

1. `HttpMCPServer` routes the POST and reads `Mcp-Session-Id`. A present-but-unknown id is 404
   before the body is touched; the tombstone map supplies the reason (`D_supersede_sessions`).
2. The body is parsed. `initialize` and `ping` are answered by the handler directly; anything
   else with no session header is 400 (`D_session_gate_two_stage`), and so is one whose
   `MCP-Protocol-Version` names a version the server does not speak (`D_protocol_version_header`).
3. The named `MCPSession` runs the request inside `runLocked`, which refreshes its last-access
   stamp and fails fast if the session was already closed.
4. `MCPToolHandler` looks the tool up, parses arguments against the declared schema, and invokes
   the `ToolFunction` with a `ToolRequest` (`D_request_records`).
5. The function returns `Content`, or throws. A thrown `MCPErrorResponseException` becomes
   `isError: true` with its message; an `MCPServerException` becomes a JSON-RPC error; anything
   else unexpected is logged at SEVERE and rendered as HTTP 500.
6. `handleRequest` writes the response. Nothing below step 5 knew it was HTTP.

**A tool call over stdio** (on the thread that called `runStdio`):

1. `runStdio` re-points `System.out` at `System.err`, keeps the real stdout in a private writer,
   and loops on `readLine` until EOF.
2. The first `initialize` creates the one session and pins it; there is no session id on the
   wire to route by (`R_mcp_stdio_framing`).
3. Every later message dispatches into that session, through the same `runLocked` chokepoint and
   the same feature handlers as the HTTP path.
4. `dispatch` throws `IllegalStateException` if the pinned session is ever closed — an invariant
   assertion, not a recovery path (`D_stdio_never_evicts`).
5. Each response is written as one UTF-8 line to the private writer. EOF on stdin ends the loop;
   a `finally` closes the session, firing `onSessionClosed`, and `stop()`s the handler.

**Session admission and eviction:**

1. Every `initialize` calls the admission policy under the handler's guard lock, with a snapshot
   of the live sessions, and gets back reject, accept, or accept-and-evict
   (`D_session_policy_injected`).
2. Still under the lock: tombstones are written for any evicted ids and the new session is put
   into the map. The lock is then released.
3. Outside the lock, each evicted session is closed in turn, blocking on its own lock until any
   in-flight request finishes.
4. Independently, and only when `HttpMCPServer.start()` asked for it, a once-a-minute tick
   sweeps sessions idle for thirty minutes. It uses `tryLock` and skips anything busy, so
   cleanup never blocks on a live request (`D_idle_eviction`).
5. Both paths write a tombstone and fire `onSessionClosed`, so an explicit DELETE, a supersede
   and an idle timeout are indistinguishable to a listener and distinguishable to a client.
6. Stopping either transport fires `onSessionClosed` for every session still live, so no
   listener-held resource outlives the server. `HttpMCPServer.stop()` first closes the listener,
   so no new session can arrive, then waits on each session's lock as a supersede does.

## Where to start reading

`MCPHandler` — it holds the registries, the session map and the dispatch switch, so its fields
are the whole data model and its methods name every seam. Then `MCPSession.runLocked`, which is
the one place every request passes through.

## What is deliberately absent

- **No SSE and no server-to-client push** — POST and DELETE only.
- **No authentication and no non-loopback binding** (`D_localhost_no_auth`).
- **No port discovery** — the port is configuration (`D_localhost_no_auth`).
- **No resources or prompts in the client** — added when a caller asks (`D_embedded_client`).
- **No session concept over stdio** — one implicit session per process (`D_stdio_transport`).
- **No idle eviction over stdio** (`D_stdio_never_evicts`).
- **No retry by default in the client** (`D_no_auto_retry`).
