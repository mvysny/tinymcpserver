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

import com.github.mvysny.tinymcpserver.client.MCPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tool half of the MCP protocol, asserted once and run by every client
 * implementation: {@code tools/list}, argument coercion, the three error
 * channels, and each content type.
 *
 * <p>A subclass supplies the client and says how that client reports a
 * JSON-RPC error:
 *
 * <pre>{@code
 * class TinyClientToolConformanceTest extends AbstractToolConformanceTest {
 *     protected MCPClient newClient(String url) { return new TinyMCPClient(URI.create(url)); }
 *     protected RpcError rpcErrorOf(Executable call) {
 *         MCPClientException e = assertThrows(MCPClientException.class, call);
 *         return new RpcError(e.getCode(), e.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <p>The official-SDK leg is what stops the suite being circular. See
 * D_conformance_two_clients.
 */
abstract class AbstractToolConformanceTest {

    /** Captures the arguments received by the last call to a recording tool. */
    protected static final AtomicReference<Map<String, Object>> lastCallArgs = new AtomicReference<>();

    private HttpMCPServer server;
    protected MCPClient client;

    /** Connects a client to {@code url}; the caller initializes and closes it. */
    protected abstract MCPClient newClient(String url) throws Exception;

    /**
     * Runs {@code call}, which must fail with a JSON-RPC protocol error, and
     * reports that error's code and message — the one thing the two clients
     * signal differently.
     */
    protected abstract RpcError rpcErrorOf(Executable call);

    protected static final class RpcError {
        final int code;
        final String message;

        protected RpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = new HttpMCPServer(0, "/mcp");
        server.getHandler().addTool("echo_text", "Echo a text message",
                new InputSchemaBuilder()
                        .requiredString("message", "The message to echo")
                        .build(),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("message")));

        server.getHandler().addTool("add_integers", "Add two integers",
                new InputSchemaBuilder()
                        .requiredInteger("a", "First integer")
                        .requiredInteger("b", "Second integer")
                        .build(),
                request -> MCPProtocol.Content.text(
                        String.valueOf((Integer) request.arguments().raw().get("a") + (Integer) request.arguments().raw().get("b"))));

        server.getHandler().addTool("multi_type", "Test all parameter types",
                new InputSchemaBuilder()
                        .requiredString("str_param", "A string")
                        .requiredInteger("int_param", "An integer")
                        .requiredNumber("num_param", "A number")
                        .requiredBoolean("bool_param", "A boolean")
                        .build(),
                request -> {
                    lastCallArgs.set(Map.copyOf(request.arguments().raw()));
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("optional_params", "Tool with optional parameters",
                new InputSchemaBuilder()
                        .requiredString("required_str", "Required string")
                        .optionalString("optional_str", "Optional string")
                        .build(),
                request -> {
                    lastCallArgs.set(Map.copyOf(request.arguments().raw()));
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("return_null", "Returns null content",
                new InputSchemaBuilder().build(),
                request -> null);

        server.getHandler().addTool("return_image", "Returns image content",
                new InputSchemaBuilder().build(),
                request -> MCPProtocol.Content.image("aW1hZ2VkYXRh", "image/png"));

        server.getHandler().addTool("return_audio", "Returns audio content",
                new InputSchemaBuilder().build(),
                request -> MCPProtocol.Content.audio("YXVkaW9kYXRh", "audio/wav"));

        server.getHandler().addTool("return_resource", "Returns resource content",
                new InputSchemaBuilder().build(),
                request -> {
                    MCPProtocol.ResourceContents rc = new MCPProtocol.ResourceContents();
                    rc.setUri("file:///test.txt");
                    rc.setMimeType("text/plain");
                    rc.setText("resource text");
                    return MCPProtocol.Content.resource(rc);
                });

        server.getHandler().addTool("throw_exception", "Always throws an exception",
                new InputSchemaBuilder().build(),
                request -> { throw new RuntimeException("something went wrong"); });

        server.getHandler().addTool("throw_mcp_internal_error", "Throws MCPServerException INTERNAL_ERROR",
                new InputSchemaBuilder().build(),
                request -> {
                    throw new MCPServerException(MCPServerException.INTERNAL_ERROR, "internal failure");
                });

        server.getHandler().addTool("throw_mcp_invalid_params", "Throws MCPServerException INVALID_PARAMS",
                new InputSchemaBuilder()
                        .requiredString("value", "A value to validate")
                        .build(),
                request -> {
                    throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                            "value must be non-empty");
                });

        server.getHandler().addTool("throw_mcp_custom_code", "Throws MCPServerException with custom code",
                new InputSchemaBuilder().build(),
                request -> {
                    throw new MCPServerException(-32000, "custom server error");
                });

        server.getHandler().addTool("throw_mcp_error_response", "Throws MCPErrorResponseException",
                new InputSchemaBuilder().build(),
                request -> {
                    throw new MCPErrorResponseException("clean error message");
                });

        server.getHandler().addTool("no_params_tool", "Tool with no params",
                new InputSchemaBuilder().build(),
                request -> {
                    lastCallArgs.set(Map.copyOf(request.arguments().raw()));
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("bounded_integer", "Tool with bounded integer parameter",
                new InputSchemaBuilder()
                        .requiredInteger("count", "Number of items")
                        .withMinimum(1)
                        .withMaximum(100)
                        .build(),
                request -> {
                    lastCallArgs.set(Map.copyOf(request.arguments().raw()));
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("enum_string", "Tool with enum string parameter",
                new InputSchemaBuilder()
                        .requiredString("color", "A color")
                        .withEnum("red", "green", "blue")
                        .build(),
                request -> {
                    lastCallArgs.set(Map.copyOf(request.arguments().raw()));
                    return MCPProtocol.Content.text("ok");
                });

        server.getHandler().addTool("echo_array", "Echoes an array back as JSON",
                new InputSchemaBuilder()
                        .requiredArray("items", "The items to echo")
                        .build(),
                request -> MCPProtocol.Content.json(request.arguments().raw().get("items")));

        server.getHandler().addTool("echo_object", "Echoes an object back as JSON",
                new InputSchemaBuilder()
                        .requiredObject("config", "The config to echo")
                        .build(),
                request -> MCPProtocol.Content.json(request.arguments().raw().get("config")));
        server.start();

        client = newClient(server.getUrl());
        client.initialize();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    private MCPProtocol.Tool toolNamed(String name) throws Exception {
        return client.listTools().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tool named " + name));
    }

    private static String textOf(MCPProtocol.CallToolResult result) {
        assertEquals(1, result.getContent().size());
        MCPProtocol.Content only = result.getContent().get(0);
        assertEquals("text", only.getType());
        return only.getText();
    }

    // ===== tools/list =====

    @Test
    void toolsListReturnsAllRegisteredTools() throws Exception {
        List<MCPProtocol.Tool> tools = client.listTools();
        assertNotNull(tools);
        assertEquals(18, tools.size());

        MCPProtocol.Tool echoTool = toolNamed("echo_text");
        assertEquals("Echo a text message", echoTool.getDescription());
        assertNotNull(echoTool.getInputSchema());
        assertEquals("object", echoTool.getInputSchema().getType());
        assertTrue(echoTool.getInputSchema().getProperties().containsKey("message"));
        assertTrue(echoTool.getInputSchema().getRequired().contains("message"));
    }

    @Test
    void toolsListInputSchemaPassedAsIs() throws Exception {
        MCPProtocol.Tool addTool = toolNamed("add_integers");
        assertNotNull(addTool.getInputSchema());
        assertTrue(addTool.getInputSchema().getProperties().containsKey("a"));
        assertTrue(addTool.getInputSchema().getProperties().containsKey("b"));
        assertEquals(2, addTool.getInputSchema().getRequired().size());
    }

    @Test
    void toolWithBoundedIntegerIsAdvertised() throws Exception {
        MCPProtocol.Tool tool = toolNamed("bounded_integer");
        assertNotNull(tool.getInputSchema());
        assertTrue(tool.getInputSchema().getProperties().containsKey("count"));
    }

    @Test
    void toolWithEnumStringIsAdvertised() throws Exception {
        MCPProtocol.Tool tool = toolNamed("enum_string");
        assertNotNull(tool.getInputSchema());
        assertTrue(tool.getInputSchema().getProperties().containsKey("color"));
    }

    // ===== tools/call — parameter passing =====

    @Test
    void callToolWithEmptyParams() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("return_null", Map.of());
        assertNotNull(result);
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    void callToolAllParameterTypes() throws Exception {
        lastCallArgs.set(null);
        client.callTool("multi_type", Map.of(
                "str_param", "hello",
                "int_param", 42,
                "num_param", 3.14,
                "bool_param", true));
        Map<String, Object> args = lastCallArgs.get();
        assertNotNull(args);
        assertEquals("hello", args.get("str_param"));
        assertInstanceOf(Integer.class, args.get("int_param"));
        assertEquals(42, args.get("int_param"));
        assertInstanceOf(Double.class, args.get("num_param"));
        assertEquals(3.14, (Double) args.get("num_param"), 0.001);
        assertEquals(Boolean.TRUE, args.get("bool_param"));
    }

    @Test
    void callToolIntegerCoercionWholeDouble() throws Exception {
        // 5.0 for an integer parameter is coerced to Integer(5); see D_coerce_string_numbers.
        lastCallArgs.set(null);
        client.callTool("multi_type", Map.of(
                "str_param", "x",
                "int_param", 5.0,
                "num_param", 1.0,
                "bool_param", false));
        Map<String, Object> args = lastCallArgs.get();
        assertNotNull(args);
        assertInstanceOf(Integer.class, args.get("int_param"));
        assertEquals(5, args.get("int_param"));
    }

    @Test
    void callToolIntegerCoercionFractionalDoubleReturnsError() {
        RpcError error = rpcErrorOf(() -> client.callTool("multi_type", Map.of(
                "str_param", "x",
                "int_param", 5.5,
                "num_param", 1.0,
                "bool_param", false)));
        assertEquals(-32602, error.code);
        assertEquals("Parameter 'int_param' must be a whole number, got 5.5", error.message);
    }

    @Test
    void callToolMissingRequiredParameterReturnsError() {
        RpcError error = rpcErrorOf(() -> client.callTool("echo_text", Map.of()));
        assertEquals(-32602, error.code);
        assertEquals("Missing required parameter 'message'", error.message);
    }

    @Test
    void callToolNullRequiredParameterTreatedAsMissing() {
        Map<String, Object> args = new HashMap<>();
        args.put("message", null);
        RpcError error = rpcErrorOf(() -> client.callTool("echo_text", args));
        assertEquals(-32602, error.code);
        assertEquals("Missing required parameter 'message'", error.message);
    }

    @Test
    void callToolNullOptionalParameterAbsentFromMap() throws Exception {
        lastCallArgs.set(null);
        Map<String, Object> args = new HashMap<>();
        args.put("required_str", "hello");
        args.put("optional_str", null);
        client.callTool("optional_params", args);
        Map<String, Object> received = lastCallArgs.get();
        assertNotNull(received);
        assertTrue(received.containsKey("required_str"));
        assertFalse(received.containsKey("optional_str"));
    }

    @Test
    void callToolUnknownParameterReturnsIsError() throws Exception {
        lastCallArgs.set(null);
        MCPProtocol.CallToolResult result =
                client.callTool("no_params_tool", Map.of("unknown_param", "surprise"));
        assertEquals(Boolean.TRUE, result.getIsError());
        String text = textOf(result);
        assertTrue(text.contains("Unknown parameter 'unknown_param'"), text);
        assertTrue(text.contains("no_params_tool"), text);
        assertNull(lastCallArgs.get(), "the tool must not be invoked");
    }

    @Test
    void callToolUnknownParameterDidYouMean() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("echo_text", Map.of("mesage", "hello"));
        assertEquals(Boolean.TRUE, result.getIsError());
        String text = textOf(result);
        assertTrue(text.contains("'mesage'"), text);
        assertTrue(text.contains("did you mean 'message'"), text);
    }

    @Test
    void callToolUnknownParameterListsValidParameters() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("add_integers", Map.of("x", 1, "y", 2));
        assertEquals(Boolean.TRUE, result.getIsError());
        String text = textOf(result);
        assertTrue(text.contains("Valid parameters:"), text);
        assertTrue(text.contains("a"), text);
        assertTrue(text.contains("b"), text);
    }

    @Test
    void callToolNotFoundReturnsError() {
        RpcError error = rpcErrorOf(() -> client.callTool("nonexistent_tool", Map.of()));
        assertEquals(-32601, error.code, "an unknown tool is METHOD_NOT_FOUND, not a bad parameter");
    }

    @Test
    void callToolWithBoundedIntegerValidValue() throws Exception {
        lastCallArgs.set(null);
        MCPProtocol.CallToolResult result = client.callTool("bounded_integer", Map.of("count", 50));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals(50, lastCallArgs.get().get("count"));
    }

    @Test
    void callToolWithEnumStringValidValue() throws Exception {
        lastCallArgs.set(null);
        MCPProtocol.CallToolResult result = client.callTool("enum_string", Map.of("color", "green"));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals("green", lastCallArgs.get().get("color"));
    }

    @Test
    void callToolArrayParamReceivedAsList() throws Exception {
        MCPProtocol.CallToolResult result =
                client.callTool("echo_array", Map.of("items", List.of("a", "b", "c")));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals("[\"a\",\"b\",\"c\"]", textOf(result));
    }

    @Test
    void callToolObjectParamReceivedAsMap() throws Exception {
        MCPProtocol.CallToolResult result =
                client.callTool("echo_object", Map.of("config", Map.of("key", "value")));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals("{\"key\":\"value\"}", textOf(result));
    }

    @Test
    void callToolMissingRequiredArrayParamReturnsError() {
        RpcError error = rpcErrorOf(() -> client.callTool("echo_array", Map.of()));
        assertEquals(-32602, error.code);
        assertEquals("Missing required parameter 'items'", error.message);
    }

    // ===== tools/call — return value handling =====

    @Test
    void callToolTextContent() throws Exception {
        MCPProtocol.CallToolResult result =
                client.callTool("echo_text", Map.of("message", "hello world"));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals("hello world", textOf(result));
    }

    @Test
    void callToolParameterIntegration() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("add_integers", Map.of("a", 3, "b", 7));
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertEquals("10", textOf(result));
    }

    @Test
    void callToolNullContentReturnsEmptyArray() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("return_null", Map.of());
        assertNotEquals(Boolean.TRUE, result.getIsError());
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    void callToolImageContent() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("return_image", Map.of());
        assertEquals(1, result.getContent().size());
        MCPProtocol.Content image = result.getContent().get(0);
        assertEquals("image", image.getType());
        assertEquals("aW1hZ2VkYXRh", image.getData());
        assertEquals("image/png", image.getMimeType());
    }

    @Test
    void callToolAudioContent() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("return_audio", Map.of());
        assertEquals(1, result.getContent().size());
        MCPProtocol.Content audio = result.getContent().get(0);
        assertEquals("audio", audio.getType());
        assertEquals("YXVkaW9kYXRh", audio.getData());
        assertEquals("audio/wav", audio.getMimeType());
    }

    @Test
    void callToolResourceContent() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("return_resource", Map.of());
        assertEquals(1, result.getContent().size());
        MCPProtocol.Content content = result.getContent().get(0);
        assertEquals("resource", content.getType());
        assertNotNull(content.getResource());
        assertEquals("file:///test.txt", content.getResource().getUri());
        assertEquals("resource text", content.getResource().getText());
    }

    // ===== the three error channels (D_three_error_layers) =====

    @Test
    void callToolExceptionReturnsIsErrorWithMessage() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("throw_exception", Map.of());
        assertEquals(Boolean.TRUE, result.getIsError());
        assertEquals("java.lang.RuntimeException: something went wrong", textOf(result));
    }

    @Test
    void callToolMCPServerExceptionReturnsJsonRpcError() {
        RpcError error = rpcErrorOf(() -> client.callTool("throw_mcp_internal_error", Map.of()));
        assertEquals(-32603, error.code);
        assertEquals("internal failure", error.message);
    }

    @Test
    void callToolMCPServerExceptionInvalidParamsReturnsJsonRpcError() {
        RpcError error = rpcErrorOf(
                () -> client.callTool("throw_mcp_invalid_params", Map.of("value", "test")));
        assertEquals(-32602, error.code);
        assertEquals("value must be non-empty", error.message);
    }

    @Test
    void callToolMCPServerExceptionCustomCodeReturnsJsonRpcError() {
        RpcError error = rpcErrorOf(() -> client.callTool("throw_mcp_custom_code", Map.of()));
        assertEquals(-32000, error.code);
        assertEquals("custom server error", error.message);
    }

    @Test
    void callToolMCPErrorResponseExceptionReturnsIsErrorWithCleanMessage() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("throw_mcp_error_response", Map.of());
        assertEquals(Boolean.TRUE, result.getIsError());
        assertEquals("clean error message", textOf(result));
    }

    @Test
    void callToolMCPErrorResponseExceptionDoesNotExposeClassName() throws Exception {
        MCPProtocol.CallToolResult result = client.callTool("throw_mcp_error_response", Map.of());
        assertFalse(textOf(result).contains("Exception"),
                "error message must not contain any Java class name");
    }
}
