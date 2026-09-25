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

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.github.mvysny.tinymcpserver.InputSchemaBuilder;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.MCPServerException;
import com.github.mvysny.tinymcpserver.HttpMCPServer;
import com.github.mvysny.tinymcpserver.ToolRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TinyMCPClient} against a local {@link HttpMCPServer}: the full
 * parameter-type matrix and the request-record fields ({@code _meta}, transport
 * headers), which the official-SDK leg is too coarse to cover.
 */
class TinyMCPClientTest {

    private static HttpMCPServer server;
    /** Set by the {@code capture} tool on every invocation. */
    private static final AtomicReference<ToolRequest> lastRequest = new AtomicReference<>();

    @BeforeAll
    static void startServer() {
        server = new HttpMCPServer(0, "/mcp");

        server.getHandler().addTool("capture",
                "Captures the incoming ToolRequest for inspection",
                new InputSchemaBuilder()
                        .optionalString("s", "string")
                        .optionalInteger("i", "integer")
                        .optionalNumber("n", "number")
                        .optionalBoolean("b", "boolean")
                        .optionalArray("a", "array")
                        .optionalObject("o", "object")
                        .build(),
                request -> {
                    lastRequest.set(request);
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("echo",
                "Echoes its required 'text' string",
                new InputSchemaBuilder().requiredString("text", "the text").build(),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("text")));

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void clearLastRequest() {
        lastRequest.set(null);
    }

    private MCPClient newClient() throws IOException {
        MCPClient client = new TinyMCPClient(URI.create(server.getUrl()));
        client.initialize();
        return client;
    }

    // ===== Round-trip basics =====

    @Test
    void initializeAndListTools() throws IOException {
        try (MCPClient client = new TinyMCPClient(URI.create(server.getUrl()))) {
            MCPProtocol.InitializeResult init = client.initialize();
            assertEquals("2025-11-25", init.getProtocolVersion());

            List<MCPProtocol.Tool> tools = client.listTools();
            assertEquals(2, tools.size());
            assertTrue(tools.stream().anyMatch(t -> "capture".equals(t.getName())));
            assertTrue(tools.stream().anyMatch(t -> "echo".equals(t.getName())));
        }
    }

    @Test
    void callToolHappyPath() throws IOException {
        try (MCPClient client = newClient()) {
            MCPProtocol.CallToolResult result = client.callTool("echo", Map.of("text", "hi"));
            assertNull(result.getIsError());
            assertEquals("hi", result.getContent().get(0).getText());
        }
    }

    // ===== Parameter type matrix =====
    //
    // Server-side, after schema-driven coercion:
    //   string  -> String
    //   integer -> Integer
    //   number  -> Long (whole) or Double (fractional) — no coercion for "number"
    //   boolean -> Boolean
    //   array   -> List<Object>
    //   object  -> Map<String, Object>

    @Test
    void passesStringParameter() throws IOException {
        callCaptureWith(Map.of("s", "hello"));
        assertEquals("hello", capturedArgs().get("s"));
    }

    @Test
    void passesIntegerParameter() throws IOException {
        callCaptureWith(Map.of("i", 42));
        Object value = capturedArgs().get("i");
        assertTrue(value instanceof Integer, "expected Integer, got " + value.getClass());
        assertEquals(42, value);
    }

    @Test
    void passesIntegerSentAsWholeJsonNumber() throws IOException {
        // GSON serialises an integral double as "1.0"; an integer parameter must
        // still take it.
        callCaptureWith(Map.of("i", 1.0));
        assertEquals(1, capturedArgs().get("i"));
    }

    @Test
    void passesNumberParameter() throws IOException {
        callCaptureWith(Map.of("n", 3.14));
        Object value = capturedArgs().get("n");
        assertTrue(value instanceof Double, "expected Double, got " + value.getClass());
        assertEquals(3.14, value);
    }

    @Test
    void passesBooleanParameter() throws IOException {
        callCaptureWith(Map.of("b", true));
        assertEquals(Boolean.TRUE, capturedArgs().get("b"));
    }

    @Test
    void passesArrayParameter() throws IOException {
        callCaptureWith(Map.of("a", List.of("x", "y", "z")));
        Object value = capturedArgs().get("a");
        assertTrue(value instanceof List, "expected List, got " + value.getClass());
        assertEquals(List.of("x", "y", "z"), value);
    }

    @Test
    void passesObjectParameter() throws IOException {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "value");
        nested.put("flag", true);
        callCaptureWith(Map.of("o", nested));

        Object value = capturedArgs().get("o");
        assertTrue(value instanceof Map, "expected Map, got " + value.getClass());
        @SuppressWarnings("unchecked")
        Map<String, Object> received = (Map<String, Object>) value;
        assertEquals("value", received.get("key"));
        assertEquals(Boolean.TRUE, received.get("flag"));
    }

    @Test
    void passesAllParameterTypesInSingleCall() throws IOException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("s", "hello");
        args.put("i", 7);
        args.put("n", 2.5);
        args.put("b", false);
        args.put("a", List.of(1, 2, 3));
        args.put("o", Map.of("nested", "yes"));

        callCaptureWith(args);

        Map<String, Object> received = capturedArgs();
        assertEquals("hello", received.get("s"));
        assertEquals(7, received.get("i"));
        assertEquals(2.5, received.get("n"));
        assertEquals(Boolean.FALSE, received.get("b"));
        assertTrue(received.get("a") instanceof List);
        assertTrue(received.get("o") instanceof Map);
    }

    // ===== Optional / required parameter handling =====

    @Test
    void omittingOptionalParametersGivesEmptyArguments() throws IOException {
        callCaptureWith(Collections.emptyMap());
        assertTrue(capturedArgs().isEmpty(),
                "expected empty arguments map, got " + capturedArgs());
    }

    @Test
    void omittingOnlySomeOptionalParametersOmitsThoseKeys() throws IOException {
        callCaptureWith(Map.of("s", "only-s"));
        Map<String, Object> args = capturedArgs();
        assertEquals(1, args.size());
        assertEquals("only-s", args.get("s"));
        assertFalse(args.containsKey("i"));
        assertFalse(args.containsKey("b"));
    }

    @Test
    void missingRequiredParameterSurfacesAsClientException() throws IOException {
        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class,
                    () -> client.callTool("echo", Collections.emptyMap()));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("text"),
                    "expected message to mention the missing parameter, got: " + ex.getMessage());
        }
    }

    @Test
    void integerParameterWithFractionalValueRejected() throws IOException {
        try (MCPClient client = newClient()) {
            MCPClientException ex = assertThrows(MCPClientException.class,
                    () -> client.callTool("capture", Map.of("i", 1.5)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        }
    }

    // ===== callTool overload behaviour =====

    @Test
    void callToolStringMapOverloadAcceptsEmptyArguments() throws IOException {
        try (MCPClient client = newClient()) {
            MCPProtocol.CallToolResult result = client.callTool("capture", Map.of());
            assertNull(result.getIsError());
            assertTrue(capturedArgs().isEmpty());
        }
    }

    @Test
    void callToolWithToolRequestForwardsArguments() throws IOException {
        try (MCPClient client = newClient()) {
            ToolRequest req = new ToolRequest("capture",
                    Map.of("s", "via-record"),
                    Collections.emptyMap(),
                    null);
            client.callTool(req);
            assertEquals("via-record", capturedArgs().get("s"));
        }
    }

    @Test
    void callToolRejectsBlankName() throws IOException {
        try (MCPClient client = newClient()) {
            ToolRequest req = new ToolRequest("",
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    null);
            assertThrows(IllegalArgumentException.class, () -> client.callTool(req));
        }
    }

    @Test
    void callToolThreeArgOverloadForwardsMeta() throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("progressToken", "tkn-3arg");
        try (MCPClient client = newClient()) {
            client.callTool("capture", Map.of("s", "via-3arg"), meta);
        }
        JsonObject received = lastRequest.get().jsonRpcMeta();
        assertNotNull(received);
        assertEquals("tkn-3arg", received.get("progressToken").getAsString());
        assertEquals("via-3arg", capturedArgs().get("s"));
    }

    @Test
    void callToolThreeArgOverloadAcceptsNullMeta() throws IOException {
        try (MCPClient client = newClient()) {
            client.callTool("capture", Map.of("s", "x"), null);
        }
        assertNull(lastRequest.get().jsonRpcMeta());
    }

    // ===== _meta forwarding (D_request_records) =====

    @Test
    void metaIsForwardedToServer() throws IOException {
        JsonObject meta = new JsonObject();
        meta.add("progressToken", new JsonPrimitive("abc-123"));
        meta.addProperty("custom", 7);

        try (MCPClient client = newClient()) {
            ToolRequest req = new ToolRequest("capture",
                    Map.of("s", "with-meta"),
                    Collections.emptyMap(),
                    meta);
            client.callTool(req);
        }

        JsonObject received = lastRequest.get().jsonRpcMeta();
        assertNotNull(received, "_meta must be present on the server-side request");
        assertEquals("abc-123", received.get("progressToken").getAsString());
        assertEquals(7, received.get("custom").getAsInt());
    }

    @Test
    void absentMetaArrivesAsNull() throws IOException {
        try (MCPClient client = newClient()) {
            client.callTool("capture", Map.of("s", "no-meta"));
        }
        assertNull(lastRequest.get().jsonRpcMeta(),
                "no _meta sent → server-side jsonRpcMeta must be null");
    }

    @Test
    void transportHeadersOnToolRequestAreNotForwardedAsHttpHeaders() throws IOException {
        // transportHeaders belongs to the inbound transport; forwarding it as
        // outbound HTTP headers would clobber the session and content-type headers.
        Map<String, String> bogus = Map.of("X-Inbound-Sentinel", "should-not-leak");
        try (MCPClient client = newClient()) {
            ToolRequest req = new ToolRequest("capture",
                    Map.of("s", "headers-test"),
                    bogus,
                    null);
            client.callTool(req);
        }
        Map<String, String> serverSeen = lastRequest.get().transportHeaders();
        assertFalse(serverSeen.containsKey("X-Inbound-Sentinel"),
                "inbound transportHeaders must not be forwarded as outbound HTTP headers");
    }

    // ===== close() lifecycle =====

    @Test
    void closeIsIdempotent() throws IOException {
        MCPClient client = newClient();
        client.close();
        client.close();
    }

    @Test
    void closeBeforeInitializeIsAllowed() throws IOException {
        // No session id yet, so there is no DELETE to send.
        MCPClient client = new TinyMCPClient(URI.create(server.getUrl()));
        client.close();
        assertThrows(IllegalStateException.class, client::listTools);
    }

    // ===== helpers =====

    private static void callCaptureWith(Map<String, Object> args) throws IOException {
        try (MCPClient client = new TinyMCPClient(URI.create(server.getUrl()))) {
            client.initialize();
            MCPProtocol.CallToolResult result = client.callTool("capture", args);
            assertNull(result.getIsError(), "tool call must not be an error");
        }
    }

    private static Map<String, Object> capturedArgs() {
        ToolRequest req = lastRequest.get();
        assertNotNull(req, "tool was not invoked");
        return req.arguments().raw();
    }
}
