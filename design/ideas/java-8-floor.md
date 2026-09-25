# Lower the Java floor from 11 to 8?

## Why it might matter

The floor is about reach: an embedder is often stuck on an old JVM they can't choose, and a
class-file version error is a total failure at startup. Java 8 is where many of those
applications actually stopped. Nobody has measured how many embedders still run on 8, and that
decides whether this is worth doing at all (`Q_market`).

## Measured cost, 2026-09-25

`javac --release 8` over `mcp-server/src/main` (32 files), with gson 2.13.2 and jspecify 1.0 on
the classpath, reports **47 errors**, 24 of them in `TinyMCPClient`:

| Cause | Count | Fix |
|---|---|---|
| `java.net.http.*` (`HttpClient`, `HttpRequest`, `HttpResponse`, `BodyHandlers`, `BodyPublishers`) | 22 | only `TinyMCPClient`; see `Q_client_home` |
| `String.isBlank` | 15 | `trim().isEmpty()` or a helper |
| `List.of` / `Set.of` | 5 | `Collections.unmodifiable*(Arrays.asList(…))` or a helper |
| `List.copyOf` | 2 | `Collections.unmodifiableList(new ArrayList<>(…))` |
| `InputStream.readAllBytes` in `JsonRpcExchange` | 1 | a read loop |
| the `Charset` overload in `StdioMCPServer` | 1 | the `String` charset name |
| diamond inference on an anonymous `LinkedHashMap` in `BoundedLRUMap` | 1 | explicit type arguments |

Apart from the HTTP client, the source changes are mechanical.

Dependencies don't block it. gson 2.13.2 and jspecify 1.0 are both class-file version 52
(Java 8), JUnit 5.14 runs on 8, and `com.sun.net.httpserver` has been in the JDK since Java 6.

## Open questions

- `Q_market` — Is Java 8 worth it? Which embedders are actually stuck on 8? Without a real case,
  this cost buys reach nobody asked for.
- `Q_client_home` — `TinyMCPClient` is the one real blocker. It ships in the main source set as
  part of the library's surface, although only tests drive it (`D_embedded_client`). There are two
  options:
  - Move it into test fixtures (`java-test-fixtures`), a separate artifact. The shipped jar then
    no longer needs `HttpClient`, and the host JVM stops carrying a client it never uses. The
    client stays on 11+. `D_embedded_client` says this hasn't been worth a separate artifact so
    far, and Java 8 would change that.
  - Rewrite it on `HttpURLConnection`: real work, and a second HTTP stack to get right.

  Moving it looks cheaper, but it collides with the tests being held to the floor (next question).
- `Q_test_floor` — The tests are held to the floor too, and `testJava11` re-runs them on a real 11
  VM (`D_conformance_two_clients`). That VM run is what catches a newer class pulled in by a
  dependency, or an API reached reflectively. Going to 8 means a `testJava8` on a Java 8 JVM.
  Either the tests also compile for 8, which the test-fixtures client blocks, or `--release 8`
  covers main only and a smaller set of tests runs on 8. Which one?
- `Q_gradle` — Can the build still produce and test Java 8 artifacts on Gradle 8.14.3 with a JDK
  11–24 build JVM? Those JDKs' `javac` supports `--release 8`, and JDK 25's still accepts it with
  a deprecation warning.
