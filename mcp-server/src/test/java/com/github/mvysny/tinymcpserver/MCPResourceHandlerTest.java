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
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MCPResourceHandlerTest {

    private static ResourceFunction constResource() {
        return request -> List.of(MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "hello"));
    }

    // ===== Registration validation =====

    @Test
    void addResourceRejectsNullUri() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addResource(null, "name", "desc", "text/plain", constResource()));
    }

    @Test
    void addResourceRejectsBlankUri() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addResource("  ", "name", "desc", "text/plain", constResource()));
    }

    @Test
    void addResourceRejectsNullName() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addResource("file://x", null, "desc", "text/plain", constResource()));
    }

    @Test
    void addResourceRejectsBlankName() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addResource("file://x", "  ", "desc", "text/plain", constResource()));
    }

    @Test
    void addResourceAllowsNullDescription() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertDoesNotThrow(() ->
                handler.addResource("file://x", "name", null, "text/plain", constResource()));
    }

    @Test
    void addResourceAllowsNullMimeType() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertDoesNotThrow(() ->
                handler.addResource("file://x", "name", "desc", null, constResource()));
    }

    @Test
    void addResourceRejectsNullFunction() {
        MCPResourceHandler handler = new MCPResourceHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addResource("file://x", "name", "desc", "text/plain", null));
    }

    @Test
    void addResourceDuplicateUriThrows() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://x", "name", "desc", "text/plain", constResource());
        assertThrows(IllegalStateException.class, () ->
                handler.addResource("file://x", "other", "other", "text/plain", constResource()));
    }

    // ===== handleResourcesList =====

    private static JsonObject dispatch(java.util.function.Supplier<Object> action) {
        FakeHttpExchange exchange = new FakeHttpExchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        rpc.parsePost();
        rpc.sendResponse(action.get());
        return MCPProtocol.fromJson(exchange.getResponseBodyString(), JsonObject.class);
    }

    @Test
    void handleResourcesListEmpty() {
        MCPResourceHandler handler = new MCPResourceHandler();
        JsonObject body = dispatch(handler::handleResourcesList);
        assertEquals(0, body.getAsJsonObject("result").getAsJsonArray("resources").size());
    }

    @Test
    void handleResourcesListReturnsRegisteredDescriptors() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://readme", "README", "The README", "text/markdown", constResource());

        JsonObject body = dispatch(handler::handleResourcesList);
        JsonArray resources = body.getAsJsonObject("result").getAsJsonArray("resources");
        assertEquals(1, resources.size());

        JsonObject r = resources.get(0).getAsJsonObject();
        assertEquals("file://readme", r.get("uri").getAsString());
        assertEquals("README", r.get("name").getAsString());
        assertEquals("The README", r.get("description").getAsString());
        assertEquals("text/markdown", r.get("mimeType").getAsString());
    }

    @Test
    void handleResourcesListPreservesRegistrationOrder() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://z", "zulu", null, null, constResource());
        handler.addResource("file://a", "alpha", null, null, constResource());
        handler.addResource("file://m", "mike", null, null, constResource());

        JsonArray resources = dispatch(handler::handleResourcesList)
                .getAsJsonObject("result").getAsJsonArray("resources");
        assertEquals("file://z", resources.get(0).getAsJsonObject().get("uri").getAsString());
        assertEquals("file://a", resources.get(1).getAsJsonObject().get("uri").getAsString());
        assertEquals("file://m", resources.get(2).getAsJsonObject().get("uri").getAsString());
    }

    @Test
    void handleResourcesListOmitsNullFields() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://bare", "bare", null, null, constResource());

        JsonObject body = dispatch(handler::handleResourcesList);
        JsonObject r = body.getAsJsonObject("result").getAsJsonArray("resources")
                .get(0).getAsJsonObject();
        assertFalse(r.has("description"));
        assertFalse(r.has("mimeType"));
    }

    // ===== handleResourcesRead =====

    private static MCPProtocol.JsonRpcRequest buildReadRequest(String uri) {
        MCPProtocol.ReadResourceParams params = new MCPProtocol.ReadResourceParams();
        params.setUri(uri);
        MCPProtocol.JsonRpcRequest req = new MCPProtocol.JsonRpcRequest();
        req.setMethod("resources/read");
        req.setId(1);
        req.setParams(MCPProtocol.gson().toJsonTree(params));
        return req;
    }

    private static JsonObject doRead(MCPResourceHandler handler, MCPProtocol.JsonRpcRequest req) {
        FakeHttpExchange exchange = new FakeHttpExchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\"}");
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        rpc.parsePost();
        rpc.sendResponse(handler.handleResourcesRead(req, Collections.emptyMap()));
        return MCPProtocol.fromJson(exchange.getResponseBodyString(), JsonObject.class);
    }

    @Test
    void handleResourcesReadReturnsFunctionResult() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://hello", "hello", "desc", "text/plain",
                request -> List.of(MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "Hi!")));

        JsonObject body = doRead(handler, buildReadRequest("file://hello"));
        JsonArray contents = body.getAsJsonObject("result").getAsJsonArray("contents");
        assertEquals(1, contents.size());
        JsonObject c0 = contents.get(0).getAsJsonObject();
        assertEquals("file://hello", c0.get("uri").getAsString());
        assertEquals("text/plain", c0.get("mimeType").getAsString());
        assertEquals("Hi!", c0.get("text").getAsString());
    }

    @Test
    void handleResourcesReadPassesUriToFunction() {
        MCPResourceHandler handler = new MCPResourceHandler();
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        handler.addResource("file://capture", "capture", null, null, request -> {
            seen.set(request.uri());
            return List.of(MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "x"));
        });

        doRead(handler, buildReadRequest("file://capture"));
        assertEquals("file://capture", seen.get());
    }

    @Test
    void handleResourcesReadSupportsMultipleContents() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://multi", "multi", null, null,
                request -> List.of(
                        MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "part 1"),
                        MCPProtocol.ResourceContents.text(request.uri(), "text/plain", "part 2")));

        JsonObject body = doRead(handler, buildReadRequest("file://multi"));
        JsonArray contents = body.getAsJsonObject("result").getAsJsonArray("contents");
        assertEquals(2, contents.size());
        assertEquals("part 1", contents.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("part 2", contents.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void handleResourcesReadSupportsBlobContents() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://icon.png", "icon", null, "image/png",
                request -> List.of(MCPProtocol.ResourceContents.blob(request.uri(), "image/png", "AAAA")));

        JsonObject body = doRead(handler, buildReadRequest("file://icon.png"));
        JsonObject c0 = body.getAsJsonObject("result").getAsJsonArray("contents")
                .get(0).getAsJsonObject();
        assertEquals("AAAA", c0.get("blob").getAsString());
        assertFalse(c0.has("text"));
    }

    @Test
    void handleResourcesReadEmptyListIsAllowed() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://empty", "empty", null, null, request -> Collections.emptyList());

        JsonObject body = doRead(handler, buildReadRequest("file://empty"));
        assertEquals(0, body.getAsJsonObject("result").getAsJsonArray("contents").size());
    }

    @Test
    void handleResourcesReadUnknownUriThrows() {
        MCPResourceHandler handler = new MCPResourceHandler();
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, buildReadRequest("file://nope")));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertTrue(ex.getMessage().contains("file://nope"), ex.getMessage());
    }

    @Test
    void handleResourcesReadMissingUriParamThrows() {
        MCPResourceHandler handler = new MCPResourceHandler();
        MCPProtocol.JsonRpcRequest req = new MCPProtocol.JsonRpcRequest();
        req.setMethod("resources/read");
        req.setId(1);
        req.setParams(MCPProtocol.gson().toJsonTree(new MCPProtocol.ReadResourceParams()));

        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, req));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void handleResourcesReadBlankUriParamThrows() {
        MCPResourceHandler handler = new MCPResourceHandler();
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, buildReadRequest("   ")));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void handleResourcesReadNullResultThrowsInternalError() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://nully", "nully", null, null, request -> null);
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, buildReadRequest("file://nully")));
        assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
    }

    @Test
    void handleResourcesReadFunctionExceptionBecomesInternalError() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://boom", "boom", null, null, request -> {
            throw new RuntimeException("kaboom");
        });
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, buildReadRequest("file://boom")));
        assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    }

    @Test
    void handleResourcesReadPropagatesMCPServerException() {
        MCPResourceHandler handler = new MCPResourceHandler();
        handler.addResource("file://reject", "reject", null, null, request -> {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS, "no");
        });
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doRead(handler, buildReadRequest("file://reject")));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("no", ex.getMessage());
    }
}
