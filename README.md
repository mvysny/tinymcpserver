# TinyMCPServer

A minimal Model Context Protocol server in pure Java, built to be embedded in someone else's
application. HTTP and stdio transports, tools, resources and prompts, plus a small HTTP client
to call one — over the JDK's `HttpServer` and GSON, with no framework underneath.
It implements the protocol and knows nothing about what the tools do.

## Why it exists

An MCP server that runs inside a host application shares that application's classloader, so
every runtime dependency it carries is one the host might already have at a different version.
The official Java SDK brings Project Reactor and two Jackson lineages; this one brings GSON.
It runs on Java 11. See `design/decisions.md`, `D_no_framework_deps`.

## Getting it

```kotlin
dependencies {
    implementation("com.github.mvysny.tinymcpserver:mcp-server:<version>")
}
```

## Using it

Register tools on an `MCPHandler`, wrap it in a transport, run the transport.

```java
MCPHandler handler = new MCPHandler()
        .setAcceptNewSession(existing ->
                new SessionDecision.AcceptAndEvict(existing, "Superseded by a new client"));
handler.addTool("greet", "Greet someone by name",
        new InputSchemaBuilder().requiredString("name", "who to greet").build(),
        req -> MCPProtocol.Content.text("Hello, " + req.arguments().getString("name")));

// in-process, inside a host application:
new HttpMCPServer(18088, "/mcp", handler).start();

// or as a standalone process an MCP client spawns:
new StdioMCPServer(handler).runStdio(System.in, System.out);
```

## The weather demo

`weather-demo` is a complete server built on the library: two tools (`get_weather`,
`get_forecast`), a resource (`weather://cities`) and a prompt (`plan_outing`), serving invented
but repeatable weather for a few cities. `WeatherMCP` is the whole of it.

Over HTTP:

```bash
./gradlew :weather-demo:run
claude mcp add --transport http weather http://127.0.0.1:18088/mcp
```

Over stdio, spawned by the client:

```bash
./gradlew :weather-demo:installDist
claude mcp add weather -- "$PWD/weather-demo/build/install/weather-demo/bin/weather-demo" --stdio
```

## What it does not do

No SSE streams, no server-to-client push, no authentication, no resumability. The HTTP
transport binds to `127.0.0.1` and nothing else.

## Building

`./gradlew` — clean, build, all tests. Build with a JDK between 11 and 24; `AGENTS.md`,
"Commands", has the extra test legs. Releasing is in `CONTRIBUTING.md`.

## License

Copyright 2026 Martin Vysny

Licensed under the [Apache License, Version 2.0](LICENSE). Every source file
carries the corresponding header; contributions are accepted under the same
license.
