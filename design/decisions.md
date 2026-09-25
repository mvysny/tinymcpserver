# Decisions

Why this server is the way it is and not otherwise — FAQ-shaped: each entry is a question and its
current answer. Rewrite the answer when it changes; delete the entry when nobody asks any more.
An entry is earned by what it would cost to reverse — half the code base — or by research the
next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels "because
that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library, the CI
host, a version bump — a comment at the site of the choice, or nothing; nothing about `design/`
itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md` is the
index. The first entry is the ruler: every later one trims to its length. **The roads not taken
are the most valuable thing in here** — only this file says which cheaper-looking design was
tried and why it lost — so a rejected road keeps every one of its losing reasons. When you have
written an entry, re-read it against the one above, check it says nothing the doc comments
already say, and check that every rejected road still carries the whole reason it lost.

---

## D_no_framework_deps — Why hand-roll MCP rather than use the official Java SDK?

This server is embedded as a jar into a host application and shares its JVM and its classloader,
so every runtime dependency it carries is one the host may already have at a different version.
`MCPProtocol` maps the wire format through plain GSON-bound POJOs; the runtime classpath is GSON
and its annotations jar, nothing else. JSON is always produced by POJO mapping — never by string
concatenation, never through GSON's element API — so there is one place where the wire shape is
defined and escaping is somebody else's solved problem.

**Why not `io.modelcontextprotocol.sdk:mcp`, the official SDK.** Rejected on measured footprint:
it brings Project Reactor and **two Jackson lineages simultaneously** — `jackson-annotations` on
the Jackson 2 coordinates and `jackson-databind` on the new Jackson 3 `tools.jackson` ones
(`R_mcp_sdk_deps`). A host already on Jackson 2 ends up with both on its classpath. That is a
clash surface no host should be asked to absorb in exchange for a feature it did not ask for.

**Why not isolate the SDK behind a child classloader instead.** Rejected — it does buy real
dependency safety, and it was the obvious third way. It loses on support cost: a second
classloader living inside someone else's application is harder to explain, harder to debug when
something loads from the wrong place, and harder to justify to a customer than a hand-written
parser they can read. A parser is a known quantity; a classloader trick inside a foreign JVM is
not.

**Why not Quarkus MCP.** Rejected twice over: the dependency footprint is the same objection as
above, and it additionally requires a Quarkus bootstrap. A host application's `main()` is fixed —
the whole premise is that we do not get to change how it starts.

**Why not Spring AI MCP.** Rejected — same footprint objection, at a larger scale.

**Why not Jackson standalone**, without the SDK. Rejected — a large dependency where GSON is
sufficient and lighter, and it reintroduces exactly the lineage clash that made the SDK
unattractive.

**The SDK still earns its place in tests.** It is a `testImplementation` only, driving this server
as a real MCP client, so conformance is checked against somebody else's reading of the spec rather
than our own. Its `mcp-test` module looks like the right harness and is not — it is the SDK's
internal tooling, not published as a supported API (`R_mcp_sdk_deps`).

**The cost we carry.** Protocol revisions are ours to follow by hand, and nothing warns us when
the spec moves.

## D_localhost_no_auth — Why bind the HTTP transport to 127.0.0.1 with no authentication?

The HTTP transport supports POST and DELETE, bound to `127.0.0.1`, with no authentication and no
SSE. It exists for the in-process case: a developer's own machine, a server embedded in an
application that developer is running, reached by a tool on the same host. Binding to loopback
makes the operating system the access control, and the developer who owns the machine owns its
security.

**Why not bind `0.0.0.0` and add authentication.** Rejected — it converts a single-machine
development tool into a network service. That means credentials to generate, store, rotate and
support, a threat model to maintain, and an attack surface, all for a reach that nothing currently
needs. The cost is not the code; it is everything that follows from having the code.

**Why no port discovery.** There is deliberately no discovery mechanism. One host application per
machine is the normal case, and the corner case of two is handled by changing the port. A
discovery protocol would be a second thing to get wrong for a situation that mostly does not
arise.

**The cost we carry.** A future remote use case reopens this entry rather than adding a flag. That
is intentional: a "just bind wider" option with the authentication story left unwritten is exactly
the shape this avoids, because the flag would ship long before the threat model did.

## D_stdio_transport — Why a second transport rather than HTTP for everything?

`StdioMCPServer` reads newline-delimited JSON-RPC over the streams it is given
(`R_mcp_stdio_framing`) and is single-session by construction; HTTP and stdio are mutually
exclusive on a given handler.

**Why two transports at all.** They serve two lifecycles that cannot borrow each other's. A
server embedded in a host application *cannot* use stdio, because the host owns stdout. A server
that an MCP client spawns as a subprocess strongly prefers stdio, because there is no port for the
two sides to agree on and the pipe closing is a definitive death notice — which is the property
`D_stdio_never_evicts` later turns out to depend on. The original design rejected stdio outright,
on the grounds that the in-process case owns stdout; that objection is still correct and simply
does not apply to a standalone process where nothing else writes there.

**Why not a separate `StdioMCPServer` class with its own machinery.** Rejected — the registries,
session handling and dispatch loop are identical and only the framing differs. A second
independent class would either duplicate all of it or force a shared base class out, and both are
more churn than a second thin shell over the same handler. This is what `D_handler_transport_split`
made cheap.

**Why ship it in the library rather than leave it to the embedder.** An MCP server library that a
client cannot spawn lacks the transport most MCP servers use, and the shell costs little because
the handler does the work. Built by each embedder instead, it would re-implement JSON-RPC framing,
session routing and the registries, with GSON already sitting on this classpath.

**Why not LSP-style `Content-Length` framing.** Rejected — the MCP specification prescribes
newline-delimited JSON-RPC for stdio (`R_mcp_stdio_framing`). Conforming is not negotiable, and
the two framings are easy to confuse precisely because LSP is the better-known one.

**Why not restore `System.out` on shutdown.** Considered and rejected for now: stdio-mode
processes live for one client session and exit when stdin closes, so there is nothing to restore
it for. Revisit only if a host ever needs to share a JVM between stdio mode and other code — at
which point the whole stdout-capture design deserves rethinking, not patching.

## D_handler_transport_split — Why is protocol dispatch split from transport, with the handler as the configuration object?

`MCPHandler` owns everything transport-agnostic — the tool, resource and prompt registries, the
session map and its guard lock, the shared executor, and JSON-RPC dispatch — and is also the only
object a caller configures. A transport is a shell that takes a configured handler and adds
framing. Handler methods return result POJOs or throw; the transport renders
(`D_three_error_layers`).

**Why.** Two reasons. Stdio has no `Mcp-Session-Id`, no HTTP status to surface, and different
framing; none of those concerns belong in the registries, dispatch or session cleanup, and
separating them meant stdio could be added without touching `MCPHandler` at all. And the HTTP
class had bundled framing, protocol logic, registries and lifecycle into one ~440-line file; after
the split each half is testable on its own.

**Why not keep one class and add a `runStdio()` beside `start()`.** Rejected — every stdio code
path would have to thread around HTTP-specific machinery it has no use for: header lookups, the
exchange object, status codes. Worse, stdio's single-session simplicity would be coupled to the
HTTP multi-session map's invariants, so a change to one would risk the other.

**Why not make `MCPHandler` the public class and have transports extend it.** Rejected —
composition. A second transport composes the same handler; it does not inherit a first
transport's surface, and inheritance here would mean every transport carries every other
transport's API.

**Why not pass `MCPSession.handlePost` a writer callback**, so a session could write its own
response. Rejected — it leaks transport framing into the session, where it does not belong, and
forces every transport to expose an identical writer abstraction. Returning POJOs is simpler and
keeps the seam in one place.

**Why not keep `addTool` / `addResource` / `addPrompt` as convenience delegates on the
transports.** Rejected — duplicate API surface on every transport, for one saved method call, and
it obscures which object is actually the configuration. `server.getHandler().addTool(…)` is
explicit about that.

**Why not keep the protected-hook pattern** — `acceptNewSession()` and `onSessionClosed()` as
overridable methods on the HTTP transport. Rejected for two reasons. It is a poor fit for stdio,
where the transport has no useful surface to override. And it forces any caller wanting a custom
policy to *subclass a transport*, when the policy has nothing to do with transport. The injected
form is strictly more flexible: any caller can configure it, not only a subclass.

**Why not make the handler's lifecycle caller-driven**, with explicit `handler.start()` /
`handler.stop()` outside the transport. Rejected — it adds a required call to every call site for
no gain, and transport-driven lifecycle pairs naturally with the one-handler-one-transport rule.

## D_settable_listeners — Why are the session listeners setters that lock, rather than constructor parameters?

`setAcceptNewSession`, `setOnSessionStarted` and `setOnSessionClosed` are fluent setters with
no-op defaults, settable until the first session is accepted and locked thereafter.

**Why setters at all.** Most callers set one of the three and leave the others at their
defaults: `new MCPHandler(info, instructions).setAcceptNewSession(…)`, then tools, then a
transport. A constructor taking all three makes every caller pass nulls for the parts it does not
care about, and it blocks a caller that builds a handler first and layers per-session lifecycle
onto it afterwards.

**Why the one-shot lockdown.** Listener semantics changing mid-flight is a footgun: a session
admitted under one policy and closed under another, with no way to reason about which applied.
The lockdown costs one boolean and preserves exactly the guarantee the constructor form was
reaching for. Pre-flight reconfiguration is the legitimate case; mid-flight is not.

**Why not non-null constructor parameters.** Rejected — they force every callback on every
caller, and block build-then-configure wiring. The defaults are sensible (accept everything, do nothing),
and a caller who forgets to override one sees the consequence — unlimited sessions, no per-session
setup — in their first integration test rather than in production.

**Why not leave the setters open for the handler's whole life.** Rejected — see the footgun
above. There is no use case for changing a session policy while sessions are live, and permitting
it would mean every reader has to consider whether it happened.

## D_session_hooks — Why keep `onSessionStarted` / `onSessionClosed` when only the tests call them?

Nothing in this repository calls them outside the tests. They stay anyway, because they are the
other half of `MCPSession`'s attributes: an embedder that holds a per-session resource — a database connection, an upstream client — opens it when the
session starts, stores it as an attribute, and closes it when the session ends. That is the usual
lifecycle for a session-scoped server, and this one is built to be embedded.

**Why not remove both.** Rejected — `setAttribute` would survive with no release point. A session
leaves by DELETE, supersede, idle eviction, a stdio re-`initialize` or `closeAllSessions()`, and
the tool code that stored the attribute sees none of them, so every resource held in one leaks
with its session. Removing the hooks honestly would mean removing attributes too.

**Why not keep `onSessionClosed` and drop `onSessionStarted`.** Rejected — lazy setup inside tool
code can allocate, but it scatters one concern across every registered `ToolFunction`, and it
loses the fail-fast: a throwing `onSessionStarted` withdraws the session and fails `initialize`,
so an unreachable database surfaces at the handshake instead of as the model's first confusing
tool error.

**Why not treat an uncalled hook as a pre-1.0 guess.** Rejected — what the hooks cost is already
paid and tested: the lockdown in `D_settable_listeners`, the exception containment on every close
path, and the test that a throwing close listener does not wedge eviction or `closeAllSessions`.
Removing them saves little, and the first embedder with a resource to release would put them back.

## D_three_error_layers — Why three error layers rather than one?

A failure is one of three things, and each wants a different client reaction. A dead socket
(`TransportIOException`) has nobody left to tell, so it is logged at WARNING and abandoned. A
protocol error (`MCPServerException`, carrying both a JSON-RPC code and an HTTP status) renders as
a JSON-RPC error envelope the client SDK can parse. A tool that ran and failed semantically
returns a normal result with `isError: true` and a recovery hint the model can act on. All
translation happens at one seam, `HttpMCPServer.handleRequest`; everything else throws.

**Why the third layer is tools-only.** Not our choice: `isError` is a field on `CallToolResult`
and nowhere else, so a failing resource or prompt handler has only the JSON-RPC envelope as a
structured channel (`R_mcp_iserror_tools_only`). The asymmetry is deliberate in the spec — a tool
can meaningfully partially fail, while resources and prompts are one-shot content producers.

**Why one translation seam.** Every caller speaks one idiom — `throw` — and there is exactly one
place to audit for how an exception becomes bytes.

**Why not render everything as tool-layer `isError`.** Rejected — it conflates infrastructure
failure (malformed JSON, dead session) with input validation, so the client SDK cannot tell
"retry will never help" from "fix your input and retry". That is the single most useful
distinction a client can draw, and collapsing it saves nothing.

**Why not use HTTP status codes alone, with no JSON-RPC error body.** Rejected — MCP clients
parse the envelope for error details, so status-only errors break their error handling outright.
JSON-RPC errors intentionally ride HTTP 200 in the normal case, which is exactly why the status
cannot be the only channel.

**Why not write errors inline at each site** with a `sendError` call instead of throwing.
Rejected for three reasons: it splits the error-to-response mapping across every handler; it
forces each caller to remember the right HTTP status for its error class; and a transport failure
*at the error-send site* then escapes into a second catch that tries to send again. One seam, one
rule.

**Why not separate exception types per layer.** Considered rather than rejected outright.
`MCPServerException` already carries a code field covering every protocol error; splitting it
further would make handler code choose between types at the throw site without giving any
downstream consumer a discrimination it can use.

**The catch-all.** Any `RuntimeException` reaching the seam that is neither of the two known types
is a server bug: logged at SEVERE and rendered as **HTTP 500**, not 200, so that operators and
monitoring see a genuine server-side failure rather than a protocol-level one. Handler-layer
exceptions never reach it — each handler wraps first.

## D_session_gate_two_stage — Why validate the session in two stages, around body parsing?

A POST is checked twice. Before parsing: if `Mcp-Session-Id` is present and names no live session,
return 404 immediately — every method is rejected, so the body is never read. After parsing: if
the method is neither `initialize` nor `ping` and the header was absent, return 400, quoting the
parsed request id in the error. `initialize` ignores any incoming session id entirely and always
attempts a new session, subject to the admission policy.

**Why split it around parsing.** The specification demands different answers for the two cases
(`R_mcp_session_lifecycle`): 404 tells a client its id is stale and it must start a new session;
400 tells it the id is missing. They are distinguishable to the client only if the second one can
quote its own request id back — which requires the parsed body — while the first needs nothing
but a header lookup.

**Why not a single pre-parse check.** Rejected — the not-initialized case needs the parsed method
to allow `initialize` and `ping` through, and the request id to populate a well-formed error
envelope. Neither is available before parsing.

**Why not a single post-parse check.** Rejected — it pays full body-parsing cost for requests
already doomed by their header alone, which is the cheap-to-reject case.

**Why -32002 rather than -32600 or -32602.** Rejected — those codes describe malformed input. A
session-state violation is a server-state condition, which is precisely what the
implementation-defined -32000..-32099 range exists for.

## D_protocol_version_header — Why refuse an unsupported `MCP-Protocol-Version`, and only past `initialize`?

A POST other than `initialize` or `ping` whose `MCP-Protocol-Version` names a version outside the
supported list gets HTTP 400 with -32600, and a message listing the supported versions. A missing
header passes, and so does any header on `initialize`, `ping` or `DELETE`.

**Why refuse at all.** The specification requires it (`R_mcp_protocol_version_header`), and it
cannot lock out a client newer than this server: negotiation always settles on a supported
version, the client's own if listed and the latest otherwise, so a client that sends what it
negotiated always sends one this server speaks.

**Why not on `initialize`.** A client ahead of this server may send its own newest version before
anything is negotiated; refusing it there locks it out of the step that would offer it a usable
one.

**Why a missing header passes.** The session already knows what it negotiated; the
specification's fallback exists for servers that cannot tell.

**Why not refuse any version other than the one this session negotiated.** Rejected — stricter
than the specification for no gain: a client that negotiated 2025-06-18 and sends 2025-03-26 is
refused although this server speaks both.

**Why not refuse only a malformed header.** Rejected — it breaks a MUST to protect a client that
ignores the negotiated version, which Claude Code does not (`R_mcp_protocol_version_header`).

**Why `DELETE` passes.** It is the client leaving; refusing it only keeps alive a session nobody
will use.

**Why -32600 rather than -32002.** The session is fine; the request carries a header the server
cannot honour, which is malformed input (contrast `D_session_gate_two_stage`).

## D_session_policy_injected — Why is the session-admission policy injected rather than built in?

`MCPHandler` is multi-session, which is the specification's default, and takes a policy function
that receives a snapshot of the live sessions and returns reject, accept, or accept-and-evict. A
caller that must be single-session says so in one line.

**Why not bake single-session in.** Rejected — a consumer needs one session at a time when the
resource behind its tools cannot survive two callers interleaving, and that is the consumer's
constraint to know, not the protocol's. Baking it in would push that assumption onto every reuser,
and would make this module's own multi-session tests fight their own server.

**Why a function over the live sessions rather than a count predicate.** The earlier shape took
an `IntPredicate` over the session count, which can express "cap at N" and nothing else. The
function form additionally expresses *which* sessions to evict, which is what
`D_supersede_sessions` needs; a count cannot name a victim.

**Why not queue a blocked client until the slot frees.** Rejected for two reasons: it adds a
waiting state and its attendant complexity for a scenario that does not arise in normal use, and
an agent queued behind a session that is wedged rather than busy waits indefinitely with no
signal.

**Why rejection is a hard failure with no retry.** A blocked client simply fails, with HTTP 409
and JSON-RPC -32002. There is no backoff and no retry advice, because a second client arriving is
a configuration mistake rather than a transient condition.

## D_supersede_sessions — Why does a new session supersede the existing one rather than being rejected?

`AcceptAndEvict` lets an admission policy evict the sessions it names; the displaced client's next
call gets a 404 whose message says why, drawn from a bounded map of recently-removed session ids
to reasons. Both supersede and idle eviction write one.

**Why.** The prior policy — reject a second `initialize` until the first session times out — is
correct under clean shutdown and brittle in practice. A client process that exits without sending
DELETE locks the slot for the full idle window, and the workflow this server exists to support
(one developer, one editor, occasional crashes) is the worst case for that policy. Supersede fits
the actual usage: there is at most one *intended* client at a time, so a fresh `initialize` is the
new ground truth.

**Why tombstones at all.** A client that sees "session not found" cannot distinguish "I am
talking to a stale id" from "the server restarted" from "I was kicked". Each calls for a different
recovery, and only the server knows which happened.

**Why the message deliberately omits the new session id.** Rejected road: surfacing it would tempt
the displaced client into reattaching, ending up with two clients sharing one session. In an
agent-driven setup that produces arbitrarily interleaved state mutations, which manifest as
randomly missing or duplicated tool calls and hours of debugging the wrong layer. The cure is
worse than the disease.

**Why not keep reject and rely on a shorter idle timeout.** Rejected on both halves: even a
one-minute timeout is jarring to wait out, and shortening it further starts evicting legitimate
sessions during long tool calls. It treats the symptom rather than the cause.

**Why not an imperative policy that calls `evict()` itself.** Rejected — it couples the policy to
handler internals (map removal, listener firing, tombstone writing) and forces the predicate to
run with side effects under the global lock. A declarative `SessionDecision` keeps the policy pure
and the eviction machinery in one place.

**Why not a simple supersede boolean instead of a rich return type.** Rejected — a flag collapses
the multi-session case, such as evicting the least-recently-used session when admitting the Nth. A
function returning a decision expresses reject, accept and any subset eviction in one signature.

**Why not carry the tombstone reason in a custom HTTP header.** Rejected — the JSON-RPC
`error.message` is the natural channel and already round-trips through every existing client's
error path. A header would need parallel plumbing on both ends for no gain.

**Eviction runs outside the guard lock.** Under the lock: snapshot the sessions, run the policy,
write tombstones, insert the new session. Then release, and only then close each evicted session
in turn. This keeps the global lock short while letting `close()` block on the session's own lock,
waiting for any in-flight request to quiesce, without pinning the handler.

## D_idle_eviction — Why evict idle sessions from a shared executor rather than per request?

`MCPHandler` owns one daemon `ScheduledExecutorService`, created in `start()` and shut down in
`stop()`. A tick once a minute evicts sessions untouched for thirty minutes — long enough not to
fight a legitimate pause, short enough that an agent that walked away frees the slot.

**Why `runLocked` is the chokepoint.** The tick and live requests meet in exactly one place, which
refreshes the last-access stamp and fails fast on an already-closed session. Refreshing there
rather than at the transport's entry path means anything that drives a session — tests included —
counts as activity, and "this session is alive" is recorded once rather than per caller. Pairing
it with the closed-flag check also keeps the post-eviction 404 consistent with
`D_session_gate_two_stage` without duplicating map lookups.

**Why `tryLock` rather than `lock` in the sweep.** A blocking acquire in the cleanup thread would
stall the entire pass behind any one slow request, and could deadlock outright against a tool
that scheduled work on this same executor and is waiting for it. Skipping a busy session and
retrying next tick keeps cleanup bounded and invisible from the request path.

**Why share the executor with tool handlers.** Tools already needed somewhere to park short
background work — debouncing, deferred cleanup, brief polling. Running a second executor purely
for cleanup would double the daemon-thread count and still leave tools with nowhere to schedule.
Sharing keeps the dependency surface at one field and gives tools a lifecycle already tied to
start and stop. Heavy or long-blocking work must still use its own executor, so the tick cannot
be starved.

**Why not no idle eviction at all**, the status quo this replaced. Rejected — a crashed
single-session client wedged the slot until the host application was restarted, which is a
terrible answer to a common event.

**Why not a separate executor purely for cleanup.** Rejected on both counts above: it doubles the
background thread count and gives tools nothing.

**Why not track last-access in the transport's request path only.** Rejected — test scenarios and
any future caller driving a session directly would silently age out despite being active. The
chokepoint keeps the invariant in one place.

**Why not a blocking `lock()` in the close path.** Rejected — it lets a single slow handler freeze
the whole cleanup pass, and opens a deadlock window against the shared executor.

**Why not evict opportunistically from the request thread.** Rejected for two reasons: it sprays
cleanup cost across every request, and it does nothing at all for a server that has simply gone
idle — which is the entire case being solved.

**Tests never wait.** They backdate a session's stamp and drive the tick synchronously, so the
thirty-minute path runs in milliseconds. This is why no configurable timeout is needed as a test
seam, which matters for `D_stdio_never_evicts`.

## D_stdio_never_evicts — Why is idle cleanup scheduled by the HTTP transport rather than by the handler?

`MCPHandler.start()` creates the executor and nothing else; scheduling the tick is a separate call
made only by `HttpMCPServer.start()`. A stdio session lives exactly as long as its process, and
`StdioMCPServer.dispatch` asserts it by throwing `IllegalStateException` if its pinned session is
ever closed.

**Why.**

- **Eviction exists for a failure mode stdio does not have.** `D_idle_eviction` added the tick so
  a crashed HTTP client could not wedge the slot: the server cannot tell "client died" from
  "client went quiet", so it times out. Over stdio the transport answers that question directly —
  the client *is* the parent process holding the pipe, and EOF on stdin is a definitive death
  notice. There is nothing to reclaim, so the timer can only destroy a live conversation.
- **The failure was unrecoverable, not merely wasteful.** A stdio server process — a forwarding
  proxy, since removed — sat idle for thirty-two minutes, its own handler evicted its own stdio
  session, and every later `tools/call` returned "Session expired". Nothing short of restarting
  the MCP client recovered it, because the stdio transport has no session concept at all: no id on
  the wire, no 404 for a client to interpret, and therefore no reason for any client to
  re-initialize (`R_mcp_stdio_framing`). The transport also pins the session in a field, so the
  process stays bricked.
- **Structural beats configurable.** Moving the schedule call to the transport that needs it makes
  the invariant hold by construction, which is what permits the assertion in `dispatch`.

**Why not a configurable idle timeout or an off switch.** Rejected as the primary fix: it leaves a
reachable configuration in which this happens again, and a "disabled" state for every future
reader to reason about. Also rejected as a *test* seam — the tests already drive the thirty-minute
path in milliseconds by backdating the stamp, so the knob would earn nothing there either.

**Why not make stdio recover instead**, re-initializing and replaying on a closed session.
Rejected, though it was the obvious defensive move. It papers over an eviction that should never
happen, and it would silently discard session-scoped state — whatever `onSessionStarted` set up —
in the middle of a conversation. The `IllegalStateException` is the same insight
expressed as a loud invariant rather than quiet repair.

**Why not refresh the stamp on `ping`.** Rejected — it makes the failure depend on client
keep-alive behaviour we do not control, and leaves a thirty-minute timer pointed at a session that
should never expire at all. Worth stating explicitly, because `ping` is answered without touching
the session, so anyone proposing "just have the client ping" should know it would not have worked.

**Why `IllegalStateException` rather than an `assert`.** Deliberate: `-da` would disable a bare
`assert`, and an `Error` would escape the read loop's `catch (RuntimeException)` and kill the loop
instead of producing one internal-error reply.

**Deferred, not rejected.** Distinguishing the three 404 wordings — unknown id, idle-evicted,
superseded — is worthwhile diagnostics and would have shortened this incident. It is a separate
change and no longer load-bearing now that the eviction cannot happen.

## D_embedded_client — Why ship our own MCP client rather than reuse the SDK's?

The test suites need a client that runs on Java 11 (`D_conformance_two_clients`), so this module
ships a small one in a package beside the server: same jar, same `MCPProtocol` POJOs, same exception conventions,
`java.net.http.HttpClient` underneath, no new runtime dependency. The surface is `initialize`,
`listTools`, `callTool`, `close`.

**Why not use the official SDK as the client.** Rejected — it pulls in exactly the dependencies
that `D_no_framework_deps` exists to avoid, in the one module whose entire reason for existing is
not having them. It stays a test dependency, which is a different thing.

**Why a package rather than a third Gradle module.** Rejected — client and server share
`MCPProtocol` wholesale along with the exception conventions, and splitting them costs more in
cross-module coordination than it earns in cohesion. The consequence is an artifact named
`mcp-server` that also hosts a client; that is a naming wart, not a design one.

**Why HTTP only, with no stdio client.** A stdio client would exist purely to test the stdio
server, and those tests already write JSON-RPC lines into its streams and read the replies back,
which is the whole of what a stdio client does.

**Why such a small surface.** Resources and prompts are deliberately absent: no caller has asked,
and adding them speculatively means maintaining and testing a surface nobody drives. Add when a
use case appears.

**Why it ships in the main source set** although only tests use it. It is part of the library's
surface, and tests in other modules build against it. As a test fixture it would take
`java.net.http` out of the shipped jar; that has not been worth a separate artifact so far.

## D_no_auto_retry — Why does a lost session surface as an exception rather than being retried?

`TinyMCPClient` throws `MCPSessionLostException` on an HTTP 404 from a non-`initialize` call and
lets the caller decide; recovering means calling `initialize()` again.

**Why failure is the default.** MCP sessions can hold state that a fresh `initialize` silently
discards. The canonical case is a per-session map of handles handed out by an earlier call: after
a silent re-initialize, a replayed call lands on whatever object now holds that key, or on none,
and *succeeds*. More generally, any tool that accumulates context across calls — cursors,
in-progress builders, transaction handles — loses it. For a stateful caller **failure is
information**: a clear "your session is gone, re-orient" beats a call that appears to work against
state nobody intended.

**Why no opt-in retry either.** A decorator that re-initialized and replayed once was removed
in 2026-09: it had no caller, and a one-call opt-in to the silent re-initialize argued against
above undercuts the argument.

**Why not retry on `IOException` too.** Rejected — a transport failure may have been delivered and
acted upon, and the caller knows its operation's idempotency while the client does not.

**Why `isError: true` is not an exception.** It comes back as a normal result, mirroring the
server's own three-layer model (`D_three_error_layers`).

## D_request_records — Why do handler callbacks take a bundle type rather than positional arguments?

`ToolFunction` and its siblings receive one immutable object bundling the identity slot — tool name, prompt
name, resource URI — with the arguments, the transport headers, and the JSON-RPC `_meta` object.

**Why.** A single lambda registered under several tool names has to know which one was invoked,
and without the bundle the lambda's own identity is the only carrier — workable, and ugly when
many tools share one implementation. And `_meta` carries cross-cutting fields such as
`progressToken` that a handler must be able to see; without a slot for it they are silently
dropped.

**Why, durably.** JSON-RPC envelopes gain fields over time — `_meta` itself is one such addition.
A bundle absorbs a new optional field without touching a single call site; a positional signature
breaks every callback again, every time.

**Why not just add the name as a second parameter.** Rejected — cheaper for that one round and
fragile immediately: `_meta` was the very next field, and would have broken every signature a
second time. A bundle pays the conversion cost once.

**Why not pass the `MCPSession` and let handlers reach into it** for name, headers and meta.
Rejected — it couples handlers to internal session state and invites reaching for fields that
should be explicit inputs. The bundle makes the contract visible at the call site.

**Why not put `_meta` in a thread-local** on the current session. Rejected — same coupling
objection, plus thread-locals are awkward in asynchronous handlers and a nuisance in tests.

**Why a bundle for resources too**, when a resource already receives its URI. Rejected the
shortcut — symmetry across tools, prompts and resources is worth one extra type, and a future
`_meta` need on resources would immediately reopen the question.

**Transport headers are empty over stdio**, since newline-delimited JSON carries no out-of-band
metadata. Both maps are unmodifiable.

## D_structural_schema_equality — Why does `InputSchema` implement structural equality rather than each caller comparing fields?

`MCPProtocol.InputSchema` and the property descriptors it references implement deep structural
`equals` and `hashCode`: two schemas are equal when they describe the same JSON Schema document
modulo property order, with `required` compared as a **set** because that is its JSON Schema
semantics.

**Why on the type rather than at the call site.** The motivating caller is an embedder's
manifest-coherence test, comparing a tool manifest it ships against what the server's
`tools/list` returns. A bug in the
predicate surfaces either as drift that is not drift, or — far worse — as missed drift, and from
there as a model reading a schema the server does not honour. Centralising the predicate on the
type gives one place to audit, one place to test, and free reuse for any future comparison.

**Why order-insensitivity is part of the contract.** Schemas are produced by a builder and also
round-tripped through GSON, and both paths must compare equal for the same logical schema. Field
order is not part of what a schema means.

**Why `enum` is compared in order, unlike `required` and property order.** That test asks
whether the model reads what the server serves, so the line is what is guaranteed to reach the
model unchanged. A JSON array keeps its order through every JSON library, so an `enum` arrives as
authored. A JSON object is unordered (RFC 8259), and an MCP client may re-serialise the schema
before the model sees it, so the server cannot promise property order. Enum order also carries
the author's intent: a default listed first, or a natural scale such as `small, medium, large`,
and models lean towards earlier options. `required` is an array too, but it only names the
properties; its order tells the reader nothing, so it compares as a set.

**Why not a comparator inside the test.** Rejected — the next caller writes a second one, and a
fix to either does not reach the other. Equality is a property of the value, not of one use of it.

**Why not serialize both to JSON and compare strings.** Rejected for three reasons: GSON's
property order is not part of any contract, so two equal schemas can differ as text on different
days; it is slower; and it surprises tests that hold `InputSchema` instances directly.

**Why not reflective deep-equals** from a utility library. Rejected — it pulls a dependency for
one method, against `D_no_framework_deps`, and reflection would compare `required` as an ordered
list, which is the one field where order is meaningless.

## D_coerce_string_numbers — Why do numeric parameters accept string-encoded numbers?

Every numeric accessor on `Parameters` takes `"21"` for `21`. This is not defensive programming in
the abstract: the first tool call an AI client ever made against this server arrived with its
integer argument quoted, despite the schema declaring `"type": "integer"`. Models emit JSON token
by token and quote numbers routinely, and a declared schema does not stop them.

**Why not enforce the declared type and return a descriptive error.** Rejected — a cooperative
client does see the error and retry, but every retry is a wasted round trip and the tokens that go
with it, for a malformed shape we can read unambiguously. Coercion is invisible to a well-formed
request and costs a well-behaved client nothing.

**What still fails.** A string that is genuinely not a number, and a number with a fractional part
where an integer is required, both fail with `INVALID_PARAMS` naming the parameter and what was
expected. This widens the accepted input without widening what counts as valid.

**Booleans likewise.** `getBooleanOrNull` takes `"true"` and `"false"`, and nothing else a string
can say.

## D_conformance_two_clients — Why is the tool suite run twice, through two different clients?

`AbstractToolConformanceTest` asserts the whole tool half of the protocol — `tools/list`, argument
coercion, the three error channels, every content type — and never names a client. Two subclasses
supply one: `TinyClientToolConformanceTest` uses this module's `TinyMCPClient`,
`OfficialClientToolConformanceTest` an adapter over the official MCP SDK.

**Why not just the in-tree client.** It is the same codebase reading its own output. A wire format
both halves agree on but the specification does not — a field name, a content-type tag, an error
code in the wrong channel — passes forever, and the first real MCP client to connect fails. The
SDK is an independent reading of the same document, which is the only thing that makes the suite
an actual conformance check rather than a round-trip.

**Why not just the SDK, which is the stronger authority.** It publishes no Java 11 build — every
release is class-file 61 — so a suite it drives cannot run on the Java 11 floor this module is
held to. The in-tree leg is what runs there.

**Why not two separate suites, each written for its client.** That was the shape before, and the
two drifted: the SDK-driven test asserted only that an unknown tool "throws", so nobody noticed it
is `-32601` rather than `-32602` until one suite had to state the answer for both. One set of
assertions is the point; the client is a parameter.

**Why the adapter converts to this module's types.** The suite then reads the same way on both
sides. Conversion happens only on the way out, after the SDK has parsed the response, so a reply
this server malformed still fails inside the SDK — the authority is not weakened by the adapter.

**Why the SDK-only tests remain SDK-only.** `listResources`, `listPrompts`, `ping` and
`isInitialized` have no counterpart on `MCPClient`. Widening that interface to share four more
assertions would add production API for a test's benefit, so `HttpMCPServerTest` keeps them.
