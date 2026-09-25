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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Construction, binding, and teardown of {@link HttpMCPServer} — the parts
 * that sit outside a JSON-RPC exchange. Protocol behaviour lives in
 * {@link HttpMCPServerTest}; session routing in {@link HttpMCPServerSessionTest}.
 */
class HttpMCPServerLifecycleTest {

    private HttpMCPServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // ===== Constructor validation =====

    @Test
    void rejectsNegativePort() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HttpMCPServer(-1, "/mcp"));
        assertTrue(ex.getMessage().contains("port"), ex.getMessage());
    }

    @Test
    void rejectsPortAbove65535() {
        assertThrows(IllegalArgumentException.class, () -> new HttpMCPServer(65536, "/mcp"));
    }

    @Test
    void rejectsContextPathWithoutLeadingSlash() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new HttpMCPServer(0, "mcp"));
        assertTrue(ex.getMessage().contains("contextPath"), ex.getMessage());
    }

    @Test
    void rejectsNullContextPath() {
        assertThrows(NullPointerException.class, () -> new HttpMCPServer(0, null));
    }

    @Test
    void rejectsNullHandler() {
        assertThrows(NullPointerException.class, () -> new HttpMCPServer(0, "/mcp", null));
    }

    // ===== Defaults and accessors =====

    @Test
    void defaultConstructorUsesTheDefaultPortAndContextPath() {
        // Constructed but not started — the default port may well be taken.
        HttpMCPServer unstarted = new HttpMCPServer();
        assertEquals(HttpMCPServer.DEFAULT_PORT, unstarted.getPort());
        assertEquals(HttpMCPServer.DEFAULT_CONTEXT_PATH, unstarted.getContextPath());
        assertEquals("http://127.0.0.1:" + HttpMCPServer.DEFAULT_PORT + "/mcp", unstarted.getUrl());
    }

    @Test
    void getPortReportsTheRequestedPortBeforeStartAndTheBoundPortAfter() {
        server = new HttpMCPServer(0, "/mcp");
        assertEquals(0, server.getPort(), "port 0 means 'let the OS pick' until start()");

        server.start();
        int bound = server.getPort();
        assertNotEquals(0, bound);
        assertEquals("http://127.0.0.1:" + bound + "/mcp", server.getUrl());

        server.stop();
        assertEquals(0, server.getPort(), "stop() must release the bound port back to 'unbound'");
        server = null;
    }

    @Test
    void stopWithoutStartIsANoOp() {
        new HttpMCPServer(0, "/mcp").stop();
    }

    @Test
    void stopIsIdempotent() {
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.start();
        s.stop();
        s.stop();
    }

    // ===== Binding =====

    @Test
    void bindFailureSurfacesAsTransportIOException() throws IOException {
        try (ServerSocket squatter = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            HttpMCPServer blocked = new HttpMCPServer(squatter.getLocalPort(), "/mcp");
            TransportIOException ex = assertThrows(TransportIOException.class, blocked::start);
            assertTrue(ex.getMessage().contains(String.valueOf(squatter.getLocalPort())),
                    "the message should name the port it could not bind; got: " + ex.getMessage());
        }
    }

    // ===== HTTP method routing =====

    @Test
    void unsupportedHttpMethodReturns405() throws Exception {
        server = new HttpMCPServer(0, "/mcp");
        server.start();

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.getUrl()))
                .GET().build());

        assertEquals(405, response.statusCode());
        assertEquals("Method Not Allowed", response.body());
    }

    // ===== DELETE (R_mcp_session_delete) =====

    @Test
    void deleteEndsOnlyTheNamedSession() throws Exception {
        List<String> closedIds = new CopyOnWriteArrayList<>();
        server = new HttpMCPServer(0, "/mcp",
                new MCPHandler().setOnSessionClosed(s -> closedIds.add(s.getId())));
        server.start();
        String ended = initialize();
        initialize();

        HttpResponse<String> response = send(delete(ended));

        assertEquals(200, response.statusCode());
        assertEquals(List.of(ended), closedIds);
        assertEquals(1, server.getHandler().getSessionCount(), "the other session must survive");
    }

    @Test
    void deleteWithoutASessionIdIs400AndEndsNothing() throws Exception {
        server = new HttpMCPServer(0, "/mcp");
        server.start();
        initialize();

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.getUrl()))
                .DELETE().build());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains(String.valueOf(MCPServerException.INVALID_REQUEST)), response.body());
        assertEquals(1, server.getHandler().getSessionCount());
    }

    @Test
    void deleteForAnUnknownSessionIs404() throws Exception {
        server = new HttpMCPServer(0, "/mcp");
        server.start();

        HttpResponse<String> response = send(delete("no-such-session"));

        assertEquals(404, response.statusCode());
    }

    @Test
    void aRepeatedDeleteIs404() throws Exception {
        server = new HttpMCPServer(0, "/mcp");
        server.start();
        String sessionId = initialize();
        assertEquals(200, send(delete(sessionId)).statusCode());

        assertEquals(404, send(delete(sessionId)).statusCode());
    }

    // ===== helpers =====

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest delete(String sessionId) {
        return HttpRequest.newBuilder(URI.create(server.getUrl()))
                .header("Mcp-Session-Id", sessionId)
                .DELETE().build();
    }

    /** @return the new session's id */
    private String initialize() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.getUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"))
                .build());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }
}
