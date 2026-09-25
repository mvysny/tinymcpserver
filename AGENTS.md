# TinyMCPServer — AGENTS.md

## What this is

A minimal Model Context Protocol server in pure Java, built to be embedded in someone else's
application. HTTP and stdio transports, tools, resources and prompts, plus a small HTTP client
to call one — over the JDK's `HttpServer` and GSON, with no framework underneath.
It implements the protocol and knows nothing about what the tools do.

## Promises

- **Host-agnostic.** Nothing in `mcp-server` names a host application or what its tools do.
- **Embeddable without dependency risk.** GSON and the JDK — it shares a classloader with an application we do not control. See `D_no_framework_deps`.
- **Java 11 is enough.** An embedder is often stuck on an old JVM and cannot choose.

## Design docs

| File | Owns | Loaded |
|---|---|---|
| `README.md` | the pitch, how to use it, the demo, what it does not do | — |
| `CONTRIBUTING.md` | how to contribute, the test legs and the manual tests, the release flow | — |
| `AGENTS.md` (this) | promises, invariants, the module and package map, conventions, commands | every turn |
| `design/architecture.md` | how the pieces compose — the transport/protocol seam, the session lifecycle, the flows; normative | lazy |
| `design/decisions.md` | why this and not that — `D_` entries, FAQ-shaped | lazy |
| `design/research.md` | what the MCP specification and the official SDK actually do — `R_` entries with provenance | lazy |
| doc comments | what one symbol does and why it is shaped so | at the symbol |

Every fact lives in exactly one of these; the others link to it.

## Invariants

- **JSON is produced by POJO mapping through GSON**, never by string concatenation or the element API; escaping bugs are the reason.
- **Handler code throws, transport code writes.** A handler method returns a result POJO or throws `MCPServerException`; only the transport turns one into bytes. See `D_three_error_layers`.
- **Every dispatched request passes through `MCPSession.runLocked`** — the one place that refreshes last-access and fails a closed session. See `D_idle_eviction`.
- **Only the framing layer writes to the captured stdout.** Anything else printing there corrupts the stdio wire. See `D_stdio_transport`.
- **Idle cleanup is scheduled by `HttpMCPServer.start()`, never by `MCPHandler.start()`.** A stdio session evicted on a timer bricks its process. See `D_stdio_never_evicts`.

## Module map

- `mcp-server` — the library, published as `com.github.mvysny.tinymcpserver:mcp-server`.
  - `com.github.mvysny.tinymcpserver` — protocol dispatch, the session map, both transports, the tool/resource/prompt registries.
  - `com.github.mvysny.tinymcpserver.client` — the minimal HTTP MCP client that drives the test suites.
- `weather-demo` — a runnable server over the library; not published. It shows the API, so it stays small.

## Conventions

- **Java 11, plain classes, no framework**, tests included; `--release 11` enforces it, so no records and no `sealed`.
- **GSON for JSON**, `java.net.http.HttpClient` for the client side, `com.sun.net.httpserver` for the server side.
- **Tests: JUnit 5**, and the tool suite runs twice — through `TinyMCPClient` (also on Java 11) and through the official MCP SDK, which is 17-only and lives in `mcp-server/src/testOfficial` alone. See `D_conformance_two_clients`.
- **Diagnostics go to `java.util.logging`**, never to `System.out`; two audiences, two channels — the LLM reads the `isError` body, the operator reads stderr.
- **Transports compose, never inherit.** A transport takes a configured `MCPHandler`; nothing extends a transport to configure it.
- **One handler, one transport, one lifecycle cycle.** No restart, no reuse, no sharing.
- **Every `.java` and `.gradle.kts` file opens with the Apache-2.0 header** naming Martin Vysny, verbatim; the full text is `LICENSE`.
- **Pre-1.0: break APIs freely.**

## Commands

- `./gradlew` — clean, build, all tests, the doc tripwires; needs a build JDK 11–24, the range Gradle 8.14.3 runs on. The default task; CI runs it on push (`.github/workflows/gradle.yml`).
- `./gradlew testJava11` — re-runs the tests on a Java 11 JVM; registered only when Gradle finds a JDK 11 (`export JDK11=/path/to/jdk11`).
- `./gradlew :mcp-server:testOfficial` — the conformance suite through the official SDK; needs a 17+ build JDK.
- `./gradlew test --tests "com.github.mvysny.tinymcpserver.MCPToolHandlerTest"` — one class; append `.methodName` for one method.
- `./gradlew :weather-demo:run` — the demo over HTTP on `http://127.0.0.1:18088/mcp`.
- `design/verify_design_tripwires.sh` — the doc layer alone; CI runs it in a job of its own.

## Skills this project follows

- **Composition over inheritance:** subclass a framework type to *be* one, never to share code; the `cop` skill has the rules.
- **Javadoc carries the contract, not the narrative:** an example over an algorithm, no history, no referrer lists; the `writing-javadoc` skill has the rules.

## Maintenance of this file

Loaded every turn; cap 34 KB, a module's own `AGENTS.md` 10 KB. Over it, in this order:
delete what has no home — status, history, class lists, what the code already says; trim
each line to its fact plus one clause and send the explanation home — why →
`design/decisions.md`, how across symbols → `design/architecture.md`, how in one symbol →
its doc comment, what upstream does → `design/research.md`; only then a module's own
`AGENTS.md`, peripheral modules first, never the core. Never paraphrase a lazy entry into a
line here. `design/verify_design_tripwires.sh` checks the caps and the cites.
