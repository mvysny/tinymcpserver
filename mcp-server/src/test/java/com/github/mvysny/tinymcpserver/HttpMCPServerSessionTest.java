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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session gate, over raw HTTP so each test controls the {@code Mcp-Session-Id}
 * header exactly. See D_session_gate_two_stage.
 */
class HttpMCPServerSessionTest {

    private static HttpMCPServer server;
    private static HttpClient http;
    private static URI serverUri;

    @BeforeAll
    static void startServer() throws Exception {
        MCPHandler handler = new MCPHandler();
        handler.addTool("echo", "Echo tool",
                new InputSchemaBuilder().requiredString("msg", "message").build(),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("msg")));
        handler.addTool("whoami", "Returns MCPSession.getCurrent().getId()",
                new InputSchemaBuilder().build(),
                request -> MCPProtocol.Content.text(MCPSession.getCurrent().getId()));
        handler.addTool("set_attr", "Store an attribute on the current session",
                new InputSchemaBuilder()
                        .requiredString("key", "key")
                        .requiredString("value", "value")
                        .build(),
                request -> {
                    MCPSession.getCurrent().setAttribute(
                            (String) request.arguments().raw().get("key"),
                            request.arguments().raw().get("value"));
                    return MCPProtocol.Content.text("ok");
                });
        handler.addTool("get_attr", "Read an attribute from the current session",
                new InputSchemaBuilder().requiredString("key", "key").build(),
                request -> {
                    Object v = MCPSession.getCurrent().getAttribute(
                            (String) request.arguments().raw().get("key"));
                    return MCPProtocol.Content.text(v == null ? "<null>" : v.toString());
                });
        handler.addPrompt("greet", "Greet someone",
                new PromptArgumentsBuilder()
                        .required("name", "Who to greet")
                        .optional("style", "Greeting style"),
                request -> {
                    MCPProtocol.GetPromptResult r = new MCPProtocol.GetPromptResult();
                    String style = request.arguments().getOrDefault("style", "friendly");
                    r.setDescription(style + " greeting");
                    MCPProtocol.PromptMessage msg = new MCPProtocol.PromptMessage();
                    msg.setRole("user");
                    msg.setContent(MCPProtocol.Content.text("Hello, " + request.arguments().get("name") + "!"));
                    r.setMessages(java.util.List.of(msg));
                    return r;
                });
        server = new HttpMCPServer(0, "/mcp", handler);
        server.start();
        http = HttpClient.newHttpClient();
        serverUri = URI.create(server.getUrl());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** A DELETE without {@code Mcp-Session-Id} terminates every session. */
    @BeforeEach
    void resetSession() throws Exception {
        http.send(HttpRequest.newBuilder(serverUri).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // ===== Helpers =====

    private HttpResponse<String> post(String jsonRpcBody, String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithVersion(String jsonRpcBody, String sessionId, String version)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", version)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUri).DELETE();
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonRpc(String method, int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}";
    }

    private static String jsonRpcToolsCall(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"msg\":\"hi\"}}}";
    }

    /** @return the new session's {@code Mcp-Session-Id}, or {@code null} if the header is absent */
    private String initialize() throws Exception {
        HttpResponse<String> resp = post(jsonRpc("initialize", 1), null);
        assertEquals(200, resp.statusCode(), "initialize should succeed");
        return resp.headers().firstValue("Mcp-Session-Id").orElse(null);
    }

    private static void assertJsonRpcError(HttpResponse<String> resp, int expectedHttpStatus,
                                           int expectedCode, String expectedMessage) {
        assertEquals(expectedHttpStatus, resp.statusCode());
        JsonObject body = MCPProtocol.fromJson(resp.body(), JsonObject.class);
        assertEquals("2.0", body.get("jsonrpc").getAsString());
        JsonObject error = body.getAsJsonObject("error");
        assertNotNull(error, "expected JSON-RPC error in body");
        assertEquals(expectedCode, error.get("code").getAsInt());
        assertEquals(expectedMessage, error.get("message").getAsString());
    }

    // ===== Tests =====

    // --- MCP-Protocol-Version (D_protocol_version_header) ---

    private static final String UNSUPPORTED_VERSION_MESSAGE =
            "Unsupported MCP-Protocol-Version '%s'. This server supports 2024-11-05, 2025-03-26, "
                    + "2025-06-18, 2025-11-25; send the version negotiated at initialize.";

    @Test
    void toolsCallWithSupportedVersionSucceeds() throws Exception {
        String sid = initialize();
        HttpResponse<String> resp = postWithVersion(jsonRpcToolsCall(2), sid, "2025-06-18");
        assertEquals(200, resp.statusCode(), resp.body());
    }

    @Test
    void toolsCallWithoutVersionSucceeds() throws Exception {
        String sid = initialize();
        HttpResponse<String> resp = post(jsonRpcToolsCall(2), sid);
        assertEquals(200, resp.statusCode(), resp.body());
    }

    @Test
    void toolsCallWithUnsupportedVersionReturns400() throws Exception {
        String sid = initialize();
        HttpResponse<String> resp = postWithVersion(jsonRpcToolsCall(2), sid, "2099-01-01");
        assertJsonRpcError(resp, 400, -32600, String.format(UNSUPPORTED_VERSION_MESSAGE, "2099-01-01"));
    }

    @Test
    void toolsCallWithMalformedVersionReturns400() throws Exception {
        String sid = initialize();
        HttpResponse<String> resp = postWithVersion(jsonRpcToolsCall(2), sid, "latest");
        assertJsonRpcError(resp, 400, -32600, String.format(UNSUPPORTED_VERSION_MESSAGE, "latest"));
    }

    @Test
    void initializeWithUnsupportedVersionHeaderStillNegotiates() throws Exception {
        // A client ahead of this server must reach negotiation, which offers it a supported version.
        HttpResponse<String> resp = postWithVersion(jsonRpc("initialize", 1), null, "2099-01-01");
        assertEquals(200, resp.statusCode(), resp.body());
    }

    @Test
    void deleteWithUnsupportedVersionStillCloses() throws Exception {
        String sid = initialize();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(serverUri).DELETE()
                        .header("Mcp-Session-Id", sid)
                        .header("MCP-Protocol-Version", "2099-01-01")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertJsonRpcError(post(jsonRpcToolsCall(2), sid), 404, -32002, "Session not found.");
    }

    @Test
    void toolsListBeforeInitializeReturns400() throws Exception {
        HttpResponse<String> resp = post(jsonRpc("tools/list", 1), null);
        assertJsonRpcError(resp, 400, -32002, "Server not initialized. Send 'initialize' first.");
    }

    @Test
    void toolsCallBeforeInitializeReturns400() throws Exception {
        HttpResponse<String> resp = post(jsonRpcToolsCall(1), null);
        assertJsonRpcError(resp, 400, -32002, "Server not initialized. Send 'initialize' first.");
    }

    @Test
    void toolsListWithWrongSessionIdReturns404() throws Exception {
        initialize();
        HttpResponse<String> resp = post(jsonRpc("tools/list", 2), "wrong-session-id");
        assertJsonRpcError(resp, 404, -32002, "Session not found.");
    }

    @Test
    void toolsListWithCorrectSessionIdSucceeds() throws Exception {
        String sessionId = initialize();
        assertNotNull(sessionId, "initialize should return a session ID");
        HttpResponse<String> resp = post(jsonRpc("tools/list", 2), sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject body = MCPProtocol.fromJson(resp.body(), JsonObject.class);
        assertNotNull(body.get("result"), "expected a result, not an error");
    }

    @Test
    void pingBeforeInitializeSucceeds() throws Exception {
        HttpResponse<String> resp = post(jsonRpc("ping", 1), null);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void pingAfterInitializeWithoutSessionIdSucceeds() throws Exception {
        initialize();
        HttpResponse<String> resp = post(jsonRpc("ping", 2), null);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void initializeAfterDeleteSucceeds() throws Exception {
        String firstSessionId = initialize();
        delete(firstSessionId);

        String secondSessionId = initialize();
        assertNotNull(secondSessionId);
        assertNotEquals(firstSessionId, secondSessionId, "new session should have a different ID");
    }

    @Test
    void toolsListAfterDeleteReturns400() throws Exception {
        initialize();
        delete(null);

        HttpResponse<String> resp = post(jsonRpc("tools/list", 2), null);
        assertJsonRpcError(resp, 400, -32002, "Server not initialized. Send 'initialize' first.");
    }

    @Test
    void toolsCallAfterDeleteWithStaleSessionIdReturns404() throws Exception {
        String sessionId = initialize();
        delete(sessionId);

        HttpResponse<String> resp = post(jsonRpcToolsCall(2), sessionId);
        assertJsonRpcError(resp, 404, -32002, "Session not found.");
    }

    @Test
    void unknownSessionIdWhenNoActiveSessionReturns404() throws Exception {
        HttpResponse<String> resp = post(jsonRpc("initialize", 1), "some-random-id");
        assertJsonRpcError(resp, 404, -32002, "Session not found.");
    }

    @Test
    void pingWithWrongSessionIdReturns404() throws Exception {
        initialize();
        HttpResponse<String> resp = post(jsonRpc("ping", 2), "wrong-session-id");
        assertJsonRpcError(resp, 404, -32002, "Session not found.");
    }

    // ===== Multi-session =====

    @Test
    void doubleInitializeCreatesSecondSession() throws Exception {
        String first = initialize();
        String second = initialize();
        assertNotNull(second);
        assertNotEquals(first, second, "second session should have a different ID");
    }

    @Test
    void twoSessionsOperateIndependently() throws Exception {
        String sessionA = initialize();
        String sessionB = initialize();

        HttpResponse<String> respA = post(jsonRpc("tools/list", 10), sessionA);
        assertEquals(200, respA.statusCode());
        JsonObject bodyA = MCPProtocol.fromJson(respA.body(), JsonObject.class);
        assertNotNull(bodyA.get("result"), "session A should get a result");

        HttpResponse<String> respB = post(jsonRpc("tools/list", 11), sessionB);
        assertEquals(200, respB.statusCode());
        JsonObject bodyB = MCPProtocol.fromJson(respB.body(), JsonObject.class);
        assertNotNull(bodyB.get("result"), "session B should get a result");

        delete(sessionA);

        HttpResponse<String> respA2 = post(jsonRpc("tools/list", 12), sessionA);
        assertJsonRpcError(respA2, 404, -32002, "Session not found.");

        HttpResponse<String> respB2 = post(jsonRpc("tools/list", 13), sessionB);
        assertEquals(200, respB2.statusCode());
    }

    // ===== MCPSession.getCurrent() inside tool callback =====

    private static String jsonRpcToolsCall(int id, String tool, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + argsJson + "}}";
    }

    private static String firstTextContent(HttpResponse<String> resp) {
        JsonObject body = MCPProtocol.fromJson(resp.body(), JsonObject.class);
        JsonObject result = body.getAsJsonObject("result");
        assertNotNull(result, "expected a result, got: " + resp.body());
        JsonArray content = result.getAsJsonArray("content");
        assertEquals(1, content.size());
        return content.get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void getCurrentInsideToolCallReturnsInvokingSession() throws Exception {
        String sessionId = initialize();
        HttpResponse<String> resp = post(jsonRpcToolsCall(2, "whoami", "{}"), sessionId);
        assertEquals(200, resp.statusCode());
        assertEquals(sessionId, firstTextContent(resp));
    }

    @Test
    void getCurrentThrowsOnTestThreadOutsideDispatch() {
        // The binding lives only on the HTTP dispatch thread.
        assertThrows(NullPointerException.class, MCPSession::getCurrent);
    }

    @Test
    void attributesPersistAcrossToolCallsInSameSession() throws Exception {
        String sessionId = initialize();

        HttpResponse<String> setResp = post(
                jsonRpcToolsCall(2, "set_attr", "{\"key\":\"color\",\"value\":\"blue\"}"),
                sessionId);
        assertEquals(200, setResp.statusCode());
        assertEquals("ok", firstTextContent(setResp));

        HttpResponse<String> getResp = post(
                jsonRpcToolsCall(3, "get_attr", "{\"key\":\"color\"}"),
                sessionId);
        assertEquals(200, getResp.statusCode());
        assertEquals("blue", firstTextContent(getResp));
    }

    @Test
    void attributesAreIsolatedAcrossSessions() throws Exception {
        String sessionA = initialize();
        String sessionB = initialize();

        HttpResponse<String> setA = post(
                jsonRpcToolsCall(2, "set_attr", "{\"key\":\"color\",\"value\":\"red\"}"),
                sessionA);
        assertEquals(200, setA.statusCode());

        HttpResponse<String> getB = post(
                jsonRpcToolsCall(3, "get_attr", "{\"key\":\"color\"}"),
                sessionB);
        assertEquals(200, getB.statusCode());
        assertEquals("<null>", firstTextContent(getB));

        HttpResponse<String> getA = post(
                jsonRpcToolsCall(4, "get_attr", "{\"key\":\"color\"}"),
                sessionA);
        assertEquals(200, getA.statusCode());
        assertEquals("red", firstTextContent(getA));
    }

    // ===== Prompts =====

    @Test
    void promptsListReturnsRegisteredPrompts() throws Exception {
        String sessionId = initialize();
        HttpResponse<String> resp = post(jsonRpc("prompts/list", 2), sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject body = MCPProtocol.fromJson(resp.body(), JsonObject.class);
        JsonArray prompts = body.getAsJsonObject("result").getAsJsonArray("prompts");
        assertEquals(1, prompts.size());
        assertEquals("greet", prompts.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void promptsGetReturnsExpandedResult() throws Exception {
        String sessionId = initialize();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"greet\",\"arguments\":{\"name\":\"Alice\",\"style\":\"formal\"}}}";
        HttpResponse<String> resp = post(body, sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject result = MCPProtocol.fromJson(resp.body(), JsonObject.class).getAsJsonObject("result");
        assertEquals("formal greeting", result.get("description").getAsString());
        JsonArray messages = result.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("Hello, Alice!",
                messages.get(0).getAsJsonObject().getAsJsonObject("content").get("text").getAsString());
    }

    @Test
    void promptsGetUsesDefaultForOmittedOptionalArg() throws Exception {
        String sessionId = initialize();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"greet\",\"arguments\":{\"name\":\"Bob\"}}}";
        HttpResponse<String> resp = post(body, sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject result = MCPProtocol.fromJson(resp.body(), JsonObject.class).getAsJsonObject("result");
        assertEquals("friendly greeting", result.get("description").getAsString());
    }

    @Test
    void promptsGetUnknownPromptReturnsInvalidParams() throws Exception {
        String sessionId = initialize();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}";
        HttpResponse<String> resp = post(body, sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject err = MCPProtocol.fromJson(resp.body(), JsonObject.class).getAsJsonObject("error");
        assertNotNull(err);
        assertEquals(-32602, err.get("code").getAsInt());
    }

    @Test
    void promptsGetMissingRequiredArgReturnsInvalidParams() throws Exception {
        String sessionId = initialize();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"greet\",\"arguments\":{}}}";
        HttpResponse<String> resp = post(body, sessionId);
        assertEquals(200, resp.statusCode());
        JsonObject err = MCPProtocol.fromJson(resp.body(), JsonObject.class).getAsJsonObject("error");
        assertNotNull(err);
        assertEquals(-32602, err.get("code").getAsInt());
    }

    // ===== acceptNewSession predicate =====

    @Test
    void acceptNewSessionPredicateCanRejectSecondSession() throws Exception {
        MCPHandler singleSessionHandler = new MCPHandler()
                .setAcceptNewSession(existing ->
                        existing.isEmpty() ? new SessionDecision.Accept() : new SessionDecision.Reject());
        singleSessionHandler.addTool("echo", "Echo tool",
                new InputSchemaBuilder().requiredString("msg", "message").build(),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("msg")));
        HttpMCPServer singleSessionServer = new HttpMCPServer(0, "/mcp", singleSessionHandler);
        singleSessionServer.start();
        try {
            URI uri = URI.create(singleSessionServer.getUrl());

            HttpResponse<String> resp1 = http.send(
                    HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonRpc("initialize", 1)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp1.statusCode());

            HttpResponse<String> resp2 = http.send(
                    HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonRpc("initialize", 2)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertJsonRpcError(resp2, 409, -32002, "Another session is already active");
        } finally {
            singleSessionServer.stop();
        }
    }
}
