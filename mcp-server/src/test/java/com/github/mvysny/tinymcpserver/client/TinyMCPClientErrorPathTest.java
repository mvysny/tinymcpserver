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
package com.github.mvysny.tinymcpserver.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.MCPServerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TinyMCPClient} against a scriptable HTTP server that answers
 * with whatever the test dictates — SSE framing, odd status codes, malformed
 * bodies. A conforming {@code HttpMCPServer} cannot produce these, so the
 * client's error mapping is unreachable without a fake on the wire.
 *
 * <p>{@link TinyMCPClientTest} covers the happy path against the real server.
 */
class TinyMCPClientErrorPathTest {

    private static final String SESSION_ID = "sess-1";

    private static final String INIT_RESULT =
            "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}";

    private HttpServer server;
    private URI url;
    private final List<Request> received = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ===== SSE framing (streamable-HTTP transport) =====

    @Test
    void sseFramedResponseIsUnwrapped() throws IOException {
        start((method, body) -> isInitialize(body)
                ? sse("event: message\ndata: " + envelope(1, INIT_RESULT) + "\n\n")
                : defaultReply(method, body));

        try (MCPClient client = new TinyMCPClient(url)) {
            assertEquals("2025-11-25", client.initialize().getProtocolVersion());
        }
    }

    @Test
    void sseEventSpanningMultipleDataLinesIsJoinedWithNewline() throws IOException {
        // The SSE spec concatenates an event's data: lines with \n. JSON
        // tolerates the newline, so a split envelope must still parse.
        start((method, body) -> isMethod(body, "tools/list")
                ? sse("data: {\"jsonrpc\":\"2.0\",\"id\":2,\n"
                        + "data: \"result\":{\"tools\":[]}}\n\n")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            assertTrue(client.listTools().isEmpty());
        }
    }

    @Test
    void sseEventWithoutTrailingBlankLineIsStillRead() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? sse("data: " + envelope(2, "{\"tools\":[]}"))
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            assertTrue(client.listTools().isEmpty());
        }
    }

    // ===== 404 → session lost, with the server's own reason =====

    @Test
    void sessionLostCarriesTheServerTombstoneMessage() throws IOException {
        // D_supersede_sessions: the server explains *why* the session is gone.
        // A generic client-side "session not found" would throw that away.
        start((method, body) -> isMethod(body, "tools/list")
                ? json(404, errorEnvelope(-32002, "Session superseded by a newer client."))
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPSessionLostException ex =
                    assertThrows(MCPSessionLostException.class, client::listTools);
            assertEquals("Session superseded by a newer client.", ex.getMessage());
        }
    }

    @Test
    void sessionLostFallsBackWhenTheBodyIsNotJsonRpc() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(404, "<html>not found</html>")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPSessionLostException ex =
                    assertThrows(MCPSessionLostException.class, client::listTools);
            assertTrue(ex.getMessage().contains("tools/list"),
                    "fallback should name the failed method; got: " + ex.getMessage());
        }
    }

    @Test
    void sessionLostFallsBackWhenTheErrorMessageIsEmpty() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(404, "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32002,\"message\":\"\"}}")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPSessionLostException ex =
                    assertThrows(MCPSessionLostException.class, client::listTools);
            assertTrue(ex.getMessage().contains("404"), "got: " + ex.getMessage());
        }
    }

    @Test
    void notFoundOnInitializeIsAPlainProtocolErrorNotSessionLoss() throws IOException {
        // initialize carries no session id, so a 404 there cannot mean the
        // session vanished — mapping it to MCPSessionLostException would tell
        // the caller to re-initialize, which cannot help.
        start((method, body) -> isInitialize(body)
                ? json(404, errorEnvelope(-32601, "no such endpoint"))
                : defaultReply(method, body));

        try (MCPClient client = new TinyMCPClient(url)) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::initialize);
            assertFalse(ex instanceof MCPSessionLostException);
            assertEquals(-32601, ex.getCode());
        }
    }

    // ===== Other non-2xx statuses =====

    @Test
    void non2xxWithErrorEnvelopeSurfacesTheServerCodeAndMessage() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(500, errorEnvelope(MCPServerException.INVALID_PARAMS, "bad params"))
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertEquals("bad params", ex.getMessage());
        }
    }

    @Test
    void non2xxWithoutErrorEnvelopeBecomesASyntheticInternalError() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(503, "upstream gateway is down")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("503"), "got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("upstream gateway is down"));
        }
    }

    @Test
    void non2xxWithAnEmptyBodyBecomesASyntheticInternalError() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(502, "")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("502"), "got: " + ex.getMessage());
        }
    }

    // ===== Malformed 2xx bodies =====

    @Test
    void malformedJsonBodyIsReported() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(200, "{not json")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("Malformed JSON"), "got: " + ex.getMessage());
        }
    }

    @Test
    void nonObjectBodyIsReported() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(200, "[1,2,3]")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertTrue(ex.getMessage().contains("Expected JSON-RPC object"), "got: " + ex.getMessage());
        }
    }

    @Test
    void envelopeWithNeitherResultNorErrorIsReported() throws IOException {
        start((method, body) -> isMethod(body, "tools/list")
                ? json(200, "{\"jsonrpc\":\"2.0\",\"id\":2}")
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertTrue(ex.getMessage().contains("neither"), "got: " + ex.getMessage());
        }
    }

    @Test
    void errorEnvelopeOn200IsSurfaced() throws IOException {
        // A JSON-RPC error rides a 200 in the streamable-HTTP transport.
        start((method, body) -> isMethod(body, "tools/list")
                ? json(200, errorEnvelope(MCPServerException.METHOD_NOT_FOUND, "Method not found"))
                : defaultReply(method, body));

        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::listTools);
            assertEquals(MCPServerException.METHOD_NOT_FOUND, ex.getCode());
            assertEquals("Method not found", ex.getMessage());
        }
    }

    // ===== notifications/initialized failures surface out of initialize() =====

    @Test
    void notificationRejectedWith404BecomesSessionLoss() throws IOException {
        start((method, body) -> isMethod(body, "notifications/initialized")
                ? json(404, errorEnvelope(-32002, "Session expired (idle timeout)"))
                : defaultReply(method, body));

        try (MCPClient client = new TinyMCPClient(url)) {
            MCPSessionLostException ex =
                    assertThrows(MCPSessionLostException.class, client::initialize);
            assertEquals("Session expired (idle timeout)", ex.getMessage());
        }
    }

    @Test
    void notificationRejectedWith500BecomesProtocolError() throws IOException {
        start((method, body) -> isMethod(body, "notifications/initialized")
                ? json(500, "boom")
                : defaultReply(method, body));

        try (MCPClient client = new TinyMCPClient(url)) {
            MCPClientException ex = assertThrows(MCPClientException.class, client::initialize);
            assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("notifications/initialized"), "got: " + ex.getMessage());
        }
    }

    // ===== close() sends DELETE and never throws on the server's answer =====

    @Test
    void closeSendsDeleteCarryingTheSessionId() throws IOException {
        start(TinyMCPClientErrorPathTest::defaultReply);

        MCPClient client = newClient();
        client.close();

        Request delete = lastRequest("DELETE");
        assertNotNull(delete, "close() must send a DELETE");
        assertEquals(SESSION_ID, delete.sessionId());
    }

    @Test
    void closeToleratesA404() throws IOException {
        start((method, body) -> "DELETE".equals(method)
                ? json(404, "already gone")
                : defaultReply(method, body));

        newClient().close();
    }

    @Test
    void closeToleratesAServerError() throws IOException {
        // close() is best-effort cleanup: a 500 is logged, never thrown, or
        // try-with-resources would mask the real failure in the body.
        start((method, body) -> "DELETE".equals(method)
                ? json(500, "cleanup failed")
                : defaultReply(method, body));

        newClient().close();
    }

    @Test
    void closeWithoutASessionSkipsTheDelete() throws IOException {
        start(TinyMCPClientErrorPathTest::defaultReply);

        new TinyMCPClient(url).close();

        assertNull(lastRequest("DELETE"));
    }

    @Test
    void callsAfterCloseAreRejected() throws IOException {
        start(TinyMCPClientErrorPathTest::defaultReply);

        MCPClient client = newClient();
        client.close();

        assertThrows(IllegalStateException.class, client::listTools);
        assertThrows(IllegalStateException.class, client::initialize);
        assertThrows(IllegalStateException.class, () -> client.callTool("echo", Map.of()));
    }

    // ===== Fake server =====

    /** One recorded inbound request. */
    private static final class Request {
        private final String method;
        private final String body;
        private final String sessionId;

        Request(String method, String body, String sessionId) {
            this.method = method;
            this.body = body;
            this.sessionId = sessionId;
        }

        String method() { return method; }
        String body() { return body; }
        String sessionId() { return sessionId; }
    }

    /** A canned HTTP answer. A {@code null} content type omits the header. */
    private static final class Reply {
        private final int status;
        private final String contentType;
        private final String body;

        Reply(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        int status() { return status; }
        String contentType() { return contentType; }
        String body() { return body; }
    }

    @FunctionalInterface
    private interface Responder {
        Reply reply(String httpMethod, String body);
    }

    private void start(Responder responder) throws IOException {
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/mcp", exchange -> respond(exchange, responder));
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private void respond(HttpExchange exchange, Responder responder) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Request(exchange.getRequestMethod(), body,
                exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));

        Reply reply = responder.reply(exchange.getRequestMethod(), body);
        exchange.getResponseHeaders().set("Mcp-Session-Id", SESSION_ID);
        if (reply.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    /** The conforming answer: what a real {@code HttpMCPServer} would send. */
    private static Reply defaultReply(String httpMethod, String body) {
        if ("DELETE".equals(httpMethod)) {
            return new Reply(200, null, "");
        }
        if (body.contains("\"method\":\"notifications/")) {
            return new Reply(202, null, "");
        }
        if (isInitialize(body)) {
            return json(200, envelope(1, INIT_RESULT));
        }
        return json(200, envelope(2, "{\"tools\":[]}"));
    }

    private static Reply json(int status, String body) {
        return new Reply(status, "application/json", body);
    }

    private static Reply sse(String body) {
        return new Reply(200, "text/event-stream", body);
    }

    private static boolean isInitialize(String body) {
        return isMethod(body, "initialize");
    }

    private static boolean isMethod(String body, String method) {
        return body.contains("\"method\":\"" + method + "\"");
    }

    private static String envelope(int id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    private static String errorEnvelope(int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":" + code
                + ",\"message\":" + MCPProtocol.toJson(message) + "}}";
    }

    private MCPClient newClient() throws IOException {
        MCPClient client = new TinyMCPClient(url);
        client.initialize();
        return client;
    }

    private Request lastRequest(String httpMethod) {
        synchronized (received) {
            for (int i = received.size() - 1; i >= 0; i--) {
                if (received.get(i).method().equals(httpMethod)) {
                    return received.get(i);
                }
            }
        }
        return null;
    }
}
