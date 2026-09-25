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

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MCPProtocolTest {

    /**
     * Asserts that deserializing {@code json} into {@code clazz} and re-serializing
     * produces a semantically equivalent JSON (ignoring key order and number representation).
     */
    private <T> T assertRoundTrip(String json, Class<T> clazz) {
        T obj = MCPProtocol.fromJson(json, clazz);
        assertNotNull(obj);
        assertEquals(
                JsonParser.parseString(json),
                JsonParser.parseString(MCPProtocol.toJson(obj)),
                "Round-trip JSON mismatch"
        );
        return obj;
    }

    // ===== Initialize =====

    @Test
    void initializeRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"TestClient\",\"version\":\"1.0.0\"}}}";

        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        assertEquals("2.0", req.getJsonrpc());
        assertEquals(1L, req.getId());
        assertEquals("initialize", req.getMethod());

        MCPProtocol.InitializeParams params = req.getParamsAs(MCPProtocol.InitializeParams.class);
        assertEquals("2024-11-05", params.getProtocolVersion());
        assertEquals("TestClient", params.getClientInfo().getName());
        assertEquals("1.0.0", params.getClientInfo().getVersion());
        assertTrue(params.getCapabilities().getRoots().getListChanged());
    }

    @Test
    void initializeResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true},\"resources\":{\"subscribe\":true,\"listChanged\":true},\"prompts\":{\"listChanged\":true},\"logging\":{}},\"serverInfo\":{\"name\":\"Demo MCP\",\"version\":\"0.0.1\"},\"instructions\":\"Use tools to inspect the demo app.\"}}";

        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        assertEquals(1L, resp.getId());

        MCPProtocol.InitializeResult result = resp.getResultAs(MCPProtocol.InitializeResult.class);
        assertEquals("2024-11-05", result.getProtocolVersion());
        assertEquals("Demo MCP", result.getServerInfo().getName());
        assertEquals("0.0.1", result.getServerInfo().getVersion());
        assertEquals("Use tools to inspect the demo app.", result.getInstructions());
        assertTrue(result.getCapabilities().getTools().getListChanged());
        assertTrue(result.getCapabilities().getResources().getSubscribe());
        assertTrue(result.getCapabilities().getPrompts().getListChanged());
        assertNotNull(result.getCapabilities().getLogging());
    }

    @Test
    void initializeResultDirectDeserialization() {
        String json = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"Test\",\"version\":\"1.0\"}}";
        MCPProtocol.InitializeResult result = assertRoundTrip(json, MCPProtocol.InitializeResult.class);
        assertEquals("2024-11-05", result.getProtocolVersion());
        assertEquals("Test", result.getServerInfo().getName());
        assertNull(result.getCapabilities().getTools().getListChanged());
    }

    // ===== Ping =====

    @Test
    void pingRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        assertEquals("ping", req.getMethod());
        assertEquals(2L, req.getId());
        assertNull(req.getParams());
    }

    @Test
    void pingResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        assertEquals(2L, resp.getId());
        assertNotNull(resp.getResult());
    }

    // ===== Tools =====

    @Test
    void toolsListRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"page2\"}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        assertEquals("tools/list", req.getMethod());
        MCPProtocol.ListToolsParams params = req.getParamsAs(MCPProtocol.ListToolsParams.class);
        assertEquals("page2", params.getCursor());
    }

    @Test
    void toolsListResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"get_weather\",\"description\":\"Get current weather\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"City name\"}},\"required\":[\"city\"]}}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);

        MCPProtocol.ListToolsResult result = resp.getResultAs(MCPProtocol.ListToolsResult.class);
        assertEquals(1, result.getTools().size());
        MCPProtocol.Tool tool = result.getTools().get(0);
        assertEquals("get_weather", tool.getName());
        assertEquals("Get current weather", tool.getDescription());
        assertEquals("object", tool.getInputSchema().getType());
        assertEquals("string", tool.getInputSchema().getProperties().get("city").getType());
        assertEquals("City name", tool.getInputSchema().getProperties().get("city").getDescription());
        assertEquals(1, tool.getInputSchema().getRequired().size());
        assertEquals("city", tool.getInputSchema().getRequired().get(0));
        assertNull(result.getNextCursor());
    }

    @Test
    void toolsCallRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Prague\"}}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.CallToolParams params = req.getParamsAs(MCPProtocol.CallToolParams.class);
        assertEquals("get_weather", params.getName());
        assertEquals("Prague", params.getArguments().get("city"));
    }

    @Test
    void toolsCallRequestWithIntegerArgument() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"demo_click\",\"arguments\":{\"ref\":42}}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.CallToolParams params = req.getParamsAs(MCPProtocol.CallToolParams.class);
        assertEquals("demo_click", params.getName());
        assertEquals(42L, params.getArguments().get("ref"));
    }

    @Test
    void toolsCallResponseText() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Sunny, 25C\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.CallToolResult result = resp.getResultAs(MCPProtocol.CallToolResult.class);
        assertEquals(1, result.getContent().size());
        assertEquals("text", result.getContent().get(0).getType());
        assertEquals("Sunny, 25C", result.getContent().get(0).getText());
        assertNull(result.getIsError());
    }

    @Test
    void toolsCallResponseImage() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"iVBOR...\",\"mimeType\":\"image/png\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.CallToolResult result = resp.getResultAs(MCPProtocol.CallToolResult.class);
        assertEquals("image", result.getContent().get(0).getType());
        assertEquals("iVBOR...", result.getContent().get(0).getData());
        assertEquals("image/png", result.getContent().get(0).getMimeType());
    }

    @Test
    void contentImageFromBufferedImage() throws java.io.IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        MCPProtocol.Content content = MCPProtocol.Content.image(img);
        assertEquals("image", content.getType());
        assertEquals("image/png", content.getMimeType());
        assertEquals("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAC0lEQVR4XmNgQAYAAA4AAdXbrS0AAAAASUVORK5CYII=", content.getData());
    }

    @Test
    void toolsCallResponseError() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Ref not found\"}],\"isError\":true}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.CallToolResult result = resp.getResultAs(MCPProtocol.CallToolResult.class);
        assertTrue(result.getIsError());
        assertEquals("Ref not found", result.getContent().get(0).getText());
    }

    // ===== Resources =====

    @Test
    void resourcesListResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"resources\":[{\"uri\":\"file:///main.rs\",\"name\":\"main.rs\",\"description\":\"Main source\",\"mimeType\":\"text/x-rust\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ListResourcesResult result = resp.getResultAs(MCPProtocol.ListResourcesResult.class);
        assertEquals(1, result.getResources().size());
        assertEquals("file:///main.rs", result.getResources().get(0).getUri());
        assertEquals("main.rs", result.getResources().get(0).getName());
        assertEquals("text/x-rust", result.getResources().get(0).getMimeType());
    }

    @Test
    void resourcesReadRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///main.rs\"}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.ReadResourceParams params = req.getParamsAs(MCPProtocol.ReadResourceParams.class);
        assertEquals("file:///main.rs", params.getUri());
    }

    @Test
    void resourcesReadResponseText() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"contents\":[{\"uri\":\"file:///main.rs\",\"mimeType\":\"text/x-rust\",\"text\":\"fn main() {}\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ReadResourceResult result = resp.getResultAs(MCPProtocol.ReadResourceResult.class);
        assertEquals(1, result.getContents().size());
        assertEquals("fn main() {}", result.getContents().get(0).getText());
        assertNull(result.getContents().get(0).getBlob());
    }

    @Test
    void resourcesReadResponseBlob() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"contents\":[{\"uri\":\"file:///image.png\",\"mimeType\":\"image/png\",\"blob\":\"iVBOR...\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ReadResourceResult result = resp.getResultAs(MCPProtocol.ReadResourceResult.class);
        assertEquals("iVBOR...", result.getContents().get(0).getBlob());
        assertNull(result.getContents().get(0).getText());
    }

    @Test
    void resourceTemplatesListResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"resourceTemplates\":[{\"uriTemplate\":\"file:///{path}\",\"name\":\"File\",\"description\":\"Read a file\",\"mimeType\":\"application/octet-stream\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ListResourceTemplatesResult result = resp.getResultAs(MCPProtocol.ListResourceTemplatesResult.class);
        assertEquals(1, result.getResourceTemplates().size());
        assertEquals("file:///{path}", result.getResourceTemplates().get(0).getUriTemplate());
    }

    @Test
    void subscribeRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"file:///main.rs\"}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.SubscribeParams params = req.getParamsAs(MCPProtocol.SubscribeParams.class);
        assertEquals("file:///main.rs", params.getUri());
    }

    // ===== Prompts =====

    @Test
    void promptsListResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":12,\"result\":{\"prompts\":[{\"name\":\"code_review\",\"description\":\"Review code\",\"arguments\":[{\"name\":\"code\",\"description\":\"Code to review\",\"required\":true}]}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ListPromptsResult result = resp.getResultAs(MCPProtocol.ListPromptsResult.class);
        assertEquals(1, result.getPrompts().size());
        MCPProtocol.Prompt prompt = result.getPrompts().get(0);
        assertEquals("code_review", prompt.getName());
        assertEquals(1, prompt.getArguments().size());
        assertTrue(prompt.getArguments().get(0).getRequired());
    }

    @Test
    void promptsGetRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"prompts/get\",\"params\":{\"name\":\"code_review\",\"arguments\":{\"code\":\"fn main()\"}}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.GetPromptParams params = req.getParamsAs(MCPProtocol.GetPromptParams.class);
        assertEquals("code_review", params.getName());
        assertEquals("fn main()", params.getArguments().get("code"));
    }

    @Test
    void promptsGetResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":13,\"result\":{\"description\":\"A code review prompt\",\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Please review this code.\"}}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.GetPromptResult result = resp.getResultAs(MCPProtocol.GetPromptResult.class);
        assertEquals("A code review prompt", result.getDescription());
        assertEquals(1, result.getMessages().size());
        assertEquals("user", result.getMessages().get(0).getRole());
        assertEquals("text", result.getMessages().get(0).getContent().getType());
        assertEquals("Please review this code.", result.getMessages().get(0).getContent().getText());
    }

    // ===== Completion =====

    @Test
    void completionCompleteRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"completion/complete\",\"params\":{\"ref\":{\"type\":\"ref/prompt\",\"name\":\"code_review\"},\"argument\":{\"name\":\"language\",\"value\":\"py\"}}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.CompleteParams params = req.getParamsAs(MCPProtocol.CompleteParams.class);
        assertEquals("ref/prompt", params.getRef().getType());
        assertEquals("code_review", params.getRef().getName());
        assertEquals("language", params.getArgument().getName());
        assertEquals("py", params.getArgument().getValue());
    }

    @Test
    void completionCompleteResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":14,\"result\":{\"completion\":{\"values\":[\"python\",\"pytorch\"],\"total\":10,\"hasMore\":true}}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.CompleteResult result = resp.getResultAs(MCPProtocol.CompleteResult.class);
        assertEquals(2, result.getCompletion().getValues().size());
        assertEquals("python", result.getCompletion().getValues().get(0));
        assertEquals(10, result.getCompletion().getTotal());
        assertTrue(result.getCompletion().getHasMore());
    }

    // ===== Logging =====

    @Test
    void loggingSetLevelRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"warning\"}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.SetLevelParams params = req.getParamsAs(MCPProtocol.SetLevelParams.class);
        assertEquals("warning", params.getLevel());
    }

    @Test
    void loggingMessageNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"logger\":\"mymodule\",\"data\":\"Something happened\"}}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        assertEquals("notifications/message", notif.getMethod());
        MCPProtocol.LoggingMessageParams params = notif.getParamsAs(MCPProtocol.LoggingMessageParams.class);
        assertEquals("info", params.getLevel());
        assertEquals("mymodule", params.getLogger());
    }

    // ===== Error Response =====

    @Test
    void jsonRpcErrorResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":99,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}";
        MCPProtocol.JsonRpcError err = assertRoundTrip(json, MCPProtocol.JsonRpcError.class);
        assertEquals(99L, err.getId());
        assertEquals(-32600, err.getError().getCode());
        assertEquals("Invalid Request", err.getError().getMessage());
        assertNull(err.getError().getData());
    }

    @Test
    void jsonRpcErrorResponseWithData() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":100,\"error\":{\"code\":-32601,\"message\":\"Method not found\",\"data\":\"tools/unknown\"}}";
        MCPProtocol.JsonRpcError err = assertRoundTrip(json, MCPProtocol.JsonRpcError.class);
        assertEquals(-32601, err.getError().getCode());
        assertEquals("tools/unknown", err.getError().getData().getAsString());
    }

    // ===== Notifications =====

    @Test
    void initializedNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        assertEquals("notifications/initialized", notif.getMethod());
        assertNull(notif.getParams());
    }

    @Test
    void cancelledNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":42,\"reason\":\"User cancelled\"}}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        assertEquals("notifications/cancelled", notif.getMethod());
        MCPProtocol.CancelledParams params = notif.getParamsAs(MCPProtocol.CancelledParams.class);
        assertEquals(42L, params.getRequestId());
        assertEquals("User cancelled", params.getReason());
    }

    @Test
    void progressNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\"tok1\",\"progress\":50.0,\"total\":100.0}}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        MCPProtocol.ProgressParams params = notif.getParamsAs(MCPProtocol.ProgressParams.class);
        assertEquals("tok1", params.getProgressToken());
        assertEquals(50.0, params.getProgress());
        assertEquals(100.0, params.getTotal());
    }

    @Test
    void resourceUpdatedNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/updated\",\"params\":{\"uri\":\"file:///data.json\"}}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        MCPProtocol.ResourceUpdatedParams params = notif.getParamsAs(MCPProtocol.ResourceUpdatedParams.class);
        assertEquals("file:///data.json", params.getUri());
    }

    @Test
    void toolsListChangedNotification() {
        String json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}";
        MCPProtocol.JsonRpcNotification notif = assertRoundTrip(json, MCPProtocol.JsonRpcNotification.class);
        assertEquals("notifications/tools/list_changed", notif.getMethod());
        assertNull(notif.getParams());
    }

    // ===== Sampling =====

    @Test
    void createMessageRequest() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"sampling/createMessage\",\"params\":{\"messages\":[{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"Hello\"}}],\"maxTokens\":100}}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        MCPProtocol.CreateMessageParams params = req.getParamsAs(MCPProtocol.CreateMessageParams.class);
        assertEquals(1, params.getMessages().size());
        assertEquals("user", params.getMessages().get(0).getRole());
        assertEquals("Hello", params.getMessages().get(0).getContent().getText());
        assertEquals(100, params.getMaxTokens());
    }

    @Test
    void createMessageResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":20,\"result\":{\"role\":\"assistant\",\"content\":{\"type\":\"text\",\"text\":\"Hi there!\"},\"model\":\"claude-3\",\"stopReason\":\"endTurn\"}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.CreateMessageResult result = resp.getResultAs(MCPProtocol.CreateMessageResult.class);
        assertEquals("assistant", result.getRole());
        assertEquals("Hi there!", result.getContent().getText());
        assertEquals("claude-3", result.getModel());
        assertEquals("endTurn", result.getStopReason());
    }

    // ===== Roots =====

    @Test
    void rootsListResponse() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":21,\"result\":{\"roots\":[{\"uri\":\"file:///home/user/project\",\"name\":\"My Project\"}]}}";
        MCPProtocol.JsonRpcResponse resp = assertRoundTrip(json, MCPProtocol.JsonRpcResponse.class);
        MCPProtocol.ListRootsResult result = resp.getResultAs(MCPProtocol.ListRootsResult.class);
        assertEquals(1, result.getRoots().size());
        assertEquals("file:///home/user/project", result.getRoots().get(0).getUri());
        assertEquals("My Project", result.getRoots().get(0).getName());
    }

    // ===== Embedded Resource Content =====

    @Test
    void embeddedResourceContent() {
        String json = "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///data.txt\",\"mimeType\":\"text/plain\",\"text\":\"hello\"}}";
        MCPProtocol.Content content = assertRoundTrip(json, MCPProtocol.Content.class);
        assertEquals("resource", content.getType());
        assertEquals("file:///data.txt", content.getResource().getUri());
        assertEquals("text/plain", content.getResource().getMimeType());
        assertEquals("hello", content.getResource().getText());
    }

    // ===== toString / equals / hashCode =====

    @Test
    void toStringReturnsJson() {
        MCPProtocol.Implementation impl = MCPProtocol.fromJson("{\"name\":\"Test\",\"version\":\"1.0\"}", MCPProtocol.Implementation.class);
        assertEquals("{\"name\":\"Test\",\"version\":\"1.0\"}", impl.toString());
    }

    @Test
    void equalsComparesViaJson() {
        MCPProtocol.Implementation a = MCPProtocol.fromJson("{\"name\":\"X\",\"version\":\"1\"}", MCPProtocol.Implementation.class);
        MCPProtocol.Implementation b = MCPProtocol.fromJson("{\"name\":\"X\",\"version\":\"1\"}", MCPProtocol.Implementation.class);
        MCPProtocol.Implementation c = MCPProtocol.fromJson("{\"name\":\"Y\",\"version\":\"2\"}", MCPProtocol.Implementation.class);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "not an Implementation");
    }

    @Test
    void hashCodeConsistentWithEquals() {
        MCPProtocol.Implementation a = MCPProtocol.fromJson("{\"name\":\"X\",\"version\":\"1\"}", MCPProtocol.Implementation.class);
        MCPProtocol.Implementation b = MCPProtocol.fromJson("{\"name\":\"X\",\"version\":\"1\"}", MCPProtocol.Implementation.class);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void isJsonInterfaceMethod() {
        MCPProtocol.Tool tool = new MCPProtocol.Tool();
        tool.setName("test");
        tool.setDescription("A test tool");
        MCPProtocol.IsJson isJson = tool;
        assertEquals("{\"name\":\"test\",\"description\":\"A test tool\"}", isJson.toJson());
    }

    // ===== Programmatic Construction =====

    @Test
    void constructRequestProgrammatically() {
        MCPProtocol.InitializeParams params = new MCPProtocol.InitializeParams();
        params.setProtocolVersion("2024-11-05");
        MCPProtocol.Implementation clientInfo = new MCPProtocol.Implementation();
        clientInfo.setName("MyClient");
        clientInfo.setVersion("2.0");
        params.setClientInfo(clientInfo);
        params.setCapabilities(new MCPProtocol.ClientCapabilities());

        MCPProtocol.JsonRpcRequest req = new MCPProtocol.JsonRpcRequest();
        req.setId(1L);
        req.setMethod("initialize");
        req.setParamsFrom(params);

        String json = req.toJson();
        MCPProtocol.JsonRpcRequest parsed = MCPProtocol.fromJson(json, MCPProtocol.JsonRpcRequest.class);
        assertEquals(req, parsed);

        MCPProtocol.InitializeParams parsedParams = parsed.getParamsAs(MCPProtocol.InitializeParams.class);
        assertEquals("2024-11-05", parsedParams.getProtocolVersion());
        assertEquals("MyClient", parsedParams.getClientInfo().getName());
    }

    @Test
    void constructResponseProgrammatically() {
        MCPProtocol.CallToolResult result = new MCPProtocol.CallToolResult();
        MCPProtocol.Content textContent = new MCPProtocol.Content();
        textContent.setType("text");
        textContent.setText("Result data");
        result.setContent(java.util.Collections.singletonList(textContent));

        MCPProtocol.JsonRpcResponse resp = new MCPProtocol.JsonRpcResponse();
        resp.setId(10L);
        resp.setResultFrom(result);

        String json = resp.toJson();
        MCPProtocol.JsonRpcResponse parsed = MCPProtocol.fromJson(json, MCPProtocol.JsonRpcResponse.class);
        assertEquals(resp, parsed);

        MCPProtocol.CallToolResult parsedResult = parsed.getResultAs(MCPProtocol.CallToolResult.class);
        assertEquals("Result data", parsedResult.getContent().get(0).getText());
    }

    @Test
    void constructErrorProgrammatically() {
        MCPProtocol.ErrorObject errorObj = new MCPProtocol.ErrorObject();
        errorObj.setCode(-32600);
        errorObj.setMessage("Invalid Request");

        MCPProtocol.JsonRpcError err = new MCPProtocol.JsonRpcError();
        err.setId(5L);
        err.setError(errorObj);

        String json = err.toJson();
        MCPProtocol.JsonRpcError parsed = MCPProtocol.fromJson(json, MCPProtocol.JsonRpcError.class);
        assertEquals(err, parsed);
        assertEquals(-32600, parsed.getError().getCode());
    }

    @Test
    void constructNotificationProgrammatically() {
        MCPProtocol.JsonRpcNotification notif = new MCPProtocol.JsonRpcNotification();
        notif.setMethod("notifications/initialized");

        String json = notif.toJson();
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", json);

        MCPProtocol.JsonRpcNotification parsed = MCPProtocol.fromJson(json, MCPProtocol.JsonRpcNotification.class);
        assertEquals(notif, parsed);
    }

    // ===== String ID =====

    @Test
    void requestWithStringId() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"ping\"}";
        MCPProtocol.JsonRpcRequest req = assertRoundTrip(json, MCPProtocol.JsonRpcRequest.class);
        assertEquals("abc-123", req.getId());
    }

    // ===== Unknown fields are ignored =====

    @Test
    void unknownFieldsAreIgnored() {
        String json = "{\"name\":\"Test\",\"version\":\"1.0\",\"unknownField\":\"value\"}";
        MCPProtocol.Implementation impl = MCPProtocol.fromJson(json, MCPProtocol.Implementation.class);
        assertEquals("Test", impl.getName());
        assertEquals("1.0", impl.getVersion());
    }

    // ===== Null optional fields omitted =====

    @Test
    void nullFieldsOmittedInSerialization() {
        MCPProtocol.Tool tool = new MCPProtocol.Tool();
        tool.setName("test");
        String json = tool.toJson();
        assertEquals("{\"name\":\"test\"}", json);
        assertFalse(json.contains("description"));
        assertFalse(json.contains("inputSchema"));
    }
}
