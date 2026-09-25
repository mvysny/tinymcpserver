/*
 * Copyright 2026 Martin Vysny
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.mvysny.tinymcpserver;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMCPServerTest {

    private StdioMCPServer server;
    private Thread worker;
    /** Test → server stdin. Closed by tests to signal EOF. */
    private PipedOutputStream clientOut;
    /** Test ← server stdout. */
    private BufferedReader clientIn;
    /** Set if the worker thread aborts unexpectedly. */
    private final AtomicReference<Throwable> workerError = new AtomicReference<>();

    private MCPHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        MCPProtocol.Implementation info = new MCPProtocol.Implementation();
        info.setName("Test Stdio Server");
        info.setVersion("1.0");
        handler = new MCPHandler(info, "be polite");
        server = new StdioMCPServer(handler);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientOut != null) {
            try {
                clientOut.close();
            } catch (IOException ignored) { /* already closed */ }
        }
        if (worker != null) {
            worker.join(5_000);
            assertFalse(worker.isAlive(), "runStdio did not return after EOF");
        }
        if (workerError.get() != null) {
            throw new AssertionError("runStdio worker failed", workerError.get());
        }
    }

    /** Launches {@code runStdio} on a daemon worker; returns a writer onto its stdin. */
    private BufferedWriter startWorker() throws IOException {
        PipedInputStream serverIn = new PipedInputStream(64 * 1024);
        clientOut = new PipedOutputStream(serverIn);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientInPipe = new PipedInputStream(serverOut, 64 * 1024);
        clientIn = new BufferedReader(new InputStreamReader(clientInPipe, StandardCharsets.UTF_8));

        worker = new Thread(() -> {
            try {
                server.runStdio(serverIn, serverOut);
            } catch (Throwable t) {
                workerError.set(t);
            }
        }, "stdio-server-test");
        worker.setDaemon(true);
        worker.start();
        return new BufferedWriter(new OutputStreamWriter(clientOut, StandardCharsets.UTF_8));
    }

    private static void send(BufferedWriter w, String json) throws IOException {
        w.write(json);
        w.write('\n');
        w.flush();
    }

    private JsonObject readResponse() throws IOException {
        String line = clientIn.readLine();
        assertNotNull(line, "expected response line, got EOF");
        return MCPProtocol.fromJson(line, JsonObject.class);
    }

    private static String initRequest(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
    }

    // ===== Construction & argument validation =====

    @Test
    void constructorRejectsNullHandler() {
        assertThrows(NullPointerException.class, () -> new StdioMCPServer(null));
    }

    @Test
    void defaultConstructorBuildsItsOwnHandler() {
        assertNotNull(new StdioMCPServer().getHandler());
    }

    @Test
    void getHandlerReturnsTheSuppliedHandler() {
        assertSame(handler, server.getHandler());
    }

    @Test
    void runStdioRejectsNullStreams() {
        assertThrows(IllegalArgumentException.class,
                () -> server.runStdio(null, OutputStream.nullOutputStream()));
        assertThrows(IllegalArgumentException.class,
                () -> server.runStdio(InputStream.nullInputStream(), null));
    }

    // ===== stdout protection (D_stdio_transport) =====

    /**
     * A stray {@code System.out.println} from a tool or a library would land
     * mid-frame on the protocol stream and desync the client, so writing to
     * the real stdout must divert {@code System.out} to stderr.
     */
    @Test
    void runStdioOnRealStdoutDivertsSystemOutToStderr() {
        PrintStream original = System.out;
        try {
            // Empty stdin: runStdio returns at once, writing nothing to stdout.
            server.runStdio(InputStream.nullInputStream(), original);
            assertNotSame(original, System.out, "System.out must no longer be the protocol stream");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void runStdioOnAnOtherStreamLeavesSystemOutAlone() {
        PrintStream original = System.out;
        server.runStdio(InputStream.nullInputStream(), OutputStream.nullOutputStream());
        assertSame(original, System.out);
    }

    // ===== Output transport failure =====

    @Test
    void aDeadStdoutEndsTheReadLoop() {
        // Nothing can be reported once the write side is gone — the loop must
        // give up rather than spin trying to emit an error frame.
        OutputStream dead = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("stdout closed");
            }
        };
        InputStream in = new ByteArrayInputStream(
                (initRequest(1) + "\n").getBytes(StandardCharsets.UTF_8));

        TransportIOException ex = assertThrows(TransportIOException.class,
                () -> server.runStdio(in, dead));
        assertInstanceOf(IOException.class, ex.getCause());
    }

    // ===== Core happy paths =====

    @Test
    void initializeReturnsServerInfoAndCapabilities() throws Exception {
        BufferedWriter w = startWorker();
        send(w, initRequest(1));

        JsonObject resp = readResponse();
        assertEquals("2.0", resp.get("jsonrpc").getAsString());
        assertEquals(1, resp.get("id").getAsInt());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("2025-11-25", result.get("protocolVersion").getAsString());
        assertEquals("Test Stdio Server", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertEquals("be polite", result.get("instructions").getAsString());
        assertNotNull(result.getAsJsonObject("capabilities").getAsJsonObject("tools"));
    }

    @Test
    void pingBeforeInitializeSucceeds() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

        JsonObject resp = readResponse();
        assertEquals(1, resp.get("id").getAsInt());
        assertEquals(0, resp.getAsJsonObject("result").size());
    }

    @Test
    void toolsCallExecutesRegisteredTool() throws Exception {
        handler.addTool("echo", "Echoes its argument",
                new InputSchemaBuilder().requiredString("text", "the text").build(),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("text")));
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        send(w, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hi\"}}}");
        JsonObject resp = readResponse();
        assertEquals(2, resp.get("id").getAsInt());
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals("hi", result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
    }

    @Test
    void toolsListReportsRegisteredTools() throws Exception {
        handler.addTool("noop", "Does nothing",
                new InputSchemaBuilder().build(),
                request -> null);
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        send(w, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonObject result = readResponse().getAsJsonObject("result");
        assertEquals(1, result.getAsJsonArray("tools").size());
        assertEquals("noop", result.getAsJsonArray("tools").get(0)
                .getAsJsonObject().get("name").getAsString());
    }

    // ===== Error cases =====

    @Test
    void sessionScopedMethodBeforeInitializeReturnsError() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        JsonObject resp = readResponse();
        assertEquals(1, resp.get("id").getAsInt());
        JsonObject error = resp.getAsJsonObject("error");
        assertEquals(MCPServerException.SERVER_NOT_INITIALIZED, error.get("code").getAsInt());
    }

    @Test
    void malformedJsonReturnsParseError() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "{not valid json");

        JsonObject resp = readResponse();
        assertTrue(resp.get("id").isJsonNull(), "id must be null when parsing fails");
        assertEquals(MCPServerException.PARSE_ERROR,
                resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void batchRequestReturnsInvalidRequest() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");

        JsonObject resp = readResponse();
        assertTrue(resp.get("id").isJsonNull());
        assertEquals(MCPServerException.INVALID_REQUEST,
                resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        send(w, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"does/not/exist\"}");
        JsonObject resp = readResponse();
        assertEquals(MCPServerException.METHOD_NOT_FOUND,
                resp.getAsJsonObject("error").get("code").getAsInt());
    }

    // ===== Notifications & framing =====

    @Test
    void notificationProducesNoResponse() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        send(w, initRequest(42));

        JsonObject resp = readResponse();
        assertEquals(42, resp.get("id").getAsInt());
        assertFalse(clientIn.ready(), "unexpected extra response after notification + initialize");
    }

    @Test
    void blankLinesAreIgnored() throws Exception {
        BufferedWriter w = startWorker();
        send(w, "");
        send(w, "   ");
        send(w, initRequest(1));

        JsonObject resp = readResponse();
        assertEquals(1, resp.get("id").getAsInt());
    }

    @Test
    void eofClosesRunStdio() throws Exception {
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        clientOut.close();
        clientOut = null;
        worker.join(5_000);
        assertFalse(worker.isAlive(), "runStdio must return when stdin reaches EOF");
    }

    // ===== Reinitialization (single-session) =====

    @Test
    void reinitializeReplacesSession() throws Exception {
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();
        send(w, initRequest(2));
        readResponse();
        // A smoke check: the session map is not visible from here, but routing
        // broken by the re-init would fail this ping.
        send(w, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
        JsonObject resp = readResponse();
        assertEquals(3, resp.get("id").getAsInt());
        assertNull(resp.get("error"));
    }

    @Test
    void reinitializeClosesTheReplacedSession() throws Exception {
        AtomicReference<MCPSession> started = new AtomicReference<>();
        List<MCPSession> closed = new CopyOnWriteArrayList<>();
        handler.setOnSessionStarted(started::set);
        handler.setOnSessionClosed(closed::add);
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();
        MCPSession first = started.get();

        send(w, initRequest(2));
        readResponse();

        assertEquals(List.of(first), closed);
        assertTrue(first.isClosed());
        assertNull(handler.getSession(first.getId()));
    }

    // ===== Session lifetime (D_stdio_never_evicts) =====

    /**
     * A scheduled tick would evict the session after 30 idle minutes and wedge
     * the process for good: no stdio client re-initializes. See D_stdio_never_evicts.
     */
    @Test
    void idleCleanupTickIsNotScheduledForStdio() throws Exception {
        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        ScheduledThreadPoolExecutor exec =
                (ScheduledThreadPoolExecutor) handler.getExecutor();
        assertEquals(0, exec.getQueue().size(),
                "stdio must schedule no periodic work — a queued task here is the "
                        + "idle-cleanup tick");
    }

    /**
     * The same invariant one level down: should a session ever close anyway,
     * the client must not be handed a tombstone describing an eviction it
     * has no way to undo.
     */
    @Test
    void dispatchOnAClosedSessionIsAnInternalErrorNotATombstone() throws Exception {
        AtomicReference<MCPSession> started = new AtomicReference<>();
        handler.setOnSessionStarted(started::set);

        BufferedWriter w = startWorker();
        send(w, initRequest(1));
        readResponse();

        // Force the state that must never occur, by hand.
        started.get().setLastAccessNanos(System.nanoTime() - TimeUnit.HOURS.toNanos(1));
        handler.cleanupIdleSessions();

        send(w, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonObject resp = readResponse();
        JsonObject error = resp.getAsJsonObject("error");
        assertNotNull(error, "a closed session must produce an error, not a result");
        assertEquals(MCPServerException.INTERNAL_ERROR, error.get("code").getAsInt());
        assertFalse(error.get("message").getAsString().contains("idle timeout"),
                "the idle tombstone must never reach a stdio client: it describes a "
                        + "server-side eviction the client has no way to recover from");
    }
}
