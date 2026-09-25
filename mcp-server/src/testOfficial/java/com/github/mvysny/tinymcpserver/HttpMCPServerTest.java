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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HttpMCPServerTest {

    private static HttpMCPServer server;
    private static McpSyncClient client;

    @BeforeAll
    static void startServer() throws Exception {
        MCPProtocol.Implementation serverInfo = new MCPProtocol.Implementation();
        serverInfo.setName("Test Server");
        serverInfo.setVersion("1.0");
        server = new HttpMCPServer(0, "/mcp", new MCPHandler(serverInfo, null));
        server.start();

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(server.getUrl())
                .openConnectionOnStartup(false)
                .build();

        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void stopServer() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void initializeAndConnect() {
        McpSchema.InitializeResult result = client.initialize();
        assertNotNull(result);
        assertEquals("Test Server", result.serverInfo().name());
        assertEquals("1.0", result.serverInfo().version());
        assertTrue(client.isInitialized());
    }

    @Test
    void listToolsReturnsEmpty() {
        client.initialize();
        McpSchema.ListToolsResult tools = client.listTools();
        assertNotNull(tools);
        assertTrue(tools.tools().isEmpty());
    }

    @Test
    void listResourcesReturnsEmpty() {
        client.initialize();
        McpSchema.ListResourcesResult resources = client.listResources();
        assertNotNull(resources);
        assertTrue(resources.resources().isEmpty());
    }

    @Test
    void listPromptsReturnsEmpty() {
        client.initialize();
        McpSchema.ListPromptsResult prompts = client.listPrompts();
        assertNotNull(prompts);
        assertTrue(prompts.prompts().isEmpty());
    }

    @Test
    void pingSucceeds() {
        client.initialize();
        assertDoesNotThrow(() -> client.ping());
    }

    // ===== addTool() guard =====

    @Test
    void addToolAfterStartThrows() throws Exception {
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.start();
        try {
            assertThrows(IllegalStateException.class, () ->
                    s.getHandler().addTool("my_tool", "desc", new InputSchemaBuilder().build(), request -> null));
        } finally {
            s.stop();
        }
    }

    @Test
    void addResourceAfterStartThrows() throws Exception {
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.start();
        try {
            assertThrows(IllegalStateException.class, () ->
                    s.getHandler().addResource("file://x", "x", "desc", "text/plain",
                            request -> java.util.List.of(
                                    MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "x"))));
        } finally {
            s.stop();
        }
    }

    @Test
    void resourcesListAndRead() throws Exception {
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.getHandler().addResource("file://greeting", "Greeting", "A friendly greeting", "text/plain",
                request -> java.util.List.of(
                        MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "Hello, world!")));
        s.start();
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder(s.getUrl())
                    .openConnectionOnStartup(false)
                    .build();
            try (McpSyncClient c = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(5))
                    .initializationTimeout(Duration.ofSeconds(5))
                    .build()) {
                c.initialize();

                McpSchema.ListResourcesResult listed = c.listResources();
                assertEquals(1, listed.resources().size());
                McpSchema.Resource r = listed.resources().get(0);
                assertEquals("file://greeting", r.uri());
                assertEquals("Greeting", r.name());
                assertEquals("A friendly greeting", r.description());
                assertEquals("text/plain", r.mimeType());

                McpSchema.ReadResourceResult read = c.readResource(
                        new McpSchema.ReadResourceRequest("file://greeting"));
                assertEquals(1, read.contents().size());
                McpSchema.ResourceContents contents = read.contents().get(0);
                assertInstanceOf(McpSchema.TextResourceContents.class, contents);
                McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) contents;
                assertEquals("file://greeting", text.uri());
                assertEquals("text/plain", text.mimeType());
                assertEquals("Hello, world!", text.text());
            }
        } finally {
            s.stop();
        }
    }

    @Test
    void malformedJsonReturnsJsonRpcParseError() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{not valid json"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());

        JsonObject body = MCPProtocol.fromJson(response.body(), JsonObject.class);
        assertEquals("2.0", body.get("jsonrpc").getAsString());
        assertTrue(body.get("id").isJsonNull());
        JsonObject error = body.getAsJsonObject("error");
        assertEquals(-32700, error.get("code").getAsInt());
        assertEquals("Parse error", error.get("message").getAsString());
    }

    @Test
    void unexpectedRuntimeExceptionReturnsHttp500InternalError() throws Exception {
        // The acceptNewSession predicate is the injection point: it runs inside
        // handleRequest's try block, and nothing expects it to throw.
        MCPHandler crashingHandler = new MCPHandler()
                .setAcceptNewSession(existing -> { throw new IllegalStateException("boom"); });
        HttpMCPServer crashingServer = new HttpMCPServer(0, "/mcp", crashingHandler);
        crashingServer.start();
        try {
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(crashingServer.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initBody))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            JsonObject body = MCPProtocol.fromJson(response.body(), JsonObject.class);
            JsonObject error = body.getAsJsonObject("error");
            assertEquals(MCPServerException.INTERNAL_ERROR, error.get("code").getAsInt());
            assertEquals("Internal error", error.get("message").getAsString());
        } finally {
            crashingServer.stop();
        }
    }

    @Test
    void toolRuntimeExceptionReturnsHttp200WithIsError() throws Exception {
        // A tool failure is a result the model reads, not a transport error. See D_three_error_layers.
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.getHandler().addTool("boom", "Throws",
                new InputSchemaBuilder().build(),
                request -> { throw new RuntimeException("kaboom"); });
        s.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String sessionId = initializeAndGetSessionId(http, s.getUrl());
            String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"boom\",\"arguments\":{}}}";
            HttpResponse<String> response = postWithSession(http, s.getUrl(), sessionId, body);

            assertEquals(200, response.statusCode());
            JsonObject result = MCPProtocol.fromJson(response.body(), JsonObject.class)
                    .getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            String text = result.getAsJsonArray("content").get(0)
                    .getAsJsonObject().get("text").getAsString();
            assertEquals("java.lang.RuntimeException: kaboom", text);
        } finally {
            s.stop();
        }
    }

    @Test
    void resourceRuntimeExceptionReturnsHttp200WithJsonRpcInternalError() throws Exception {
        // Unlike a tool, a resource has no isError channel: its failure is a JSON-RPC error.
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.getHandler().addResource("file://boom", "boom", "throws", "text/plain",
                request -> { throw new RuntimeException("kaboom"); });
        s.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String sessionId = initializeAndGetSessionId(http, s.getUrl());
            String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\","
                    + "\"params\":{\"uri\":\"file://boom\"}}";
            HttpResponse<String> response = postWithSession(http, s.getUrl(), sessionId, body);

            assertEquals(200, response.statusCode());
            JsonObject error = MCPProtocol.fromJson(response.body(), JsonObject.class)
                    .getAsJsonObject("error");
            assertEquals(MCPServerException.INTERNAL_ERROR, error.get("code").getAsInt());
            assertTrue(error.get("message").getAsString().contains("kaboom"),
                    error.get("message").getAsString());
        } finally {
            s.stop();
        }
    }

    @Test
    void promptRuntimeExceptionReturnsHttp200WithJsonRpcInternalError() throws Exception {
        HttpMCPServer s = new HttpMCPServer(0, "/mcp");
        s.getHandler().addPrompt("boom", "throws", new PromptArgumentsBuilder(),
                request -> { throw new RuntimeException("kaboom"); });
        s.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String sessionId = initializeAndGetSessionId(http, s.getUrl());
            String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/get\","
                    + "\"params\":{\"name\":\"boom\"}}";
            HttpResponse<String> response = postWithSession(http, s.getUrl(), sessionId, body);

            assertEquals(200, response.statusCode());
            JsonObject error = MCPProtocol.fromJson(response.body(), JsonObject.class)
                    .getAsJsonObject("error");
            assertEquals(MCPServerException.INTERNAL_ERROR, error.get("code").getAsInt());
            assertTrue(error.get("message").getAsString().contains("kaboom"),
                    error.get("message").getAsString());
        } finally {
            s.stop();
        }
    }

    private static String initializeAndGetSessionId(HttpClient http, String url) throws Exception {
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "initialize must succeed");
        return response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new AssertionError("initialize response missing Mcp-Session-Id"));
    }

    private static HttpResponse<String> postWithSession(HttpClient http, String url,
                                                         String sessionId, String jsonBody) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void batchRequestReturnsInvalidRequestError() throws Exception {
        // A batch is valid JSON, so it is -32600 (Invalid Request), not -32700 (Parse error).
        String batchBody = "[{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":2}]";
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(batchBody))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());

        JsonObject body = MCPProtocol.fromJson(response.body(), JsonObject.class);
        assertEquals("2.0", body.get("jsonrpc").getAsString());
        assertTrue(body.get("id").isJsonNull());
        JsonObject error = body.getAsJsonObject("error");
        assertEquals(-32600, error.get("code").getAsInt());
        assertEquals("Batch requests are not supported", error.get("message").getAsString());
    }
}
