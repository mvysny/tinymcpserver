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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MCPPromptHandlerTest {

    // ===== Registration validation =====

    private static PromptArgumentsBuilder emptyArgs() {
        return new PromptArgumentsBuilder();
    }

    private static PromptFunction constPrompt() {
        return request -> {
            MCPProtocol.GetPromptResult r = new MCPProtocol.GetPromptResult();
            r.setMessages(Collections.emptyList());
            return r;
        };
    }

    @Test
    void addPromptRejectsNullName() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt(null, "desc", emptyArgs(), constPrompt()));
    }

    @Test
    void addPromptRejectsBlankName() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("  ", "desc", emptyArgs(), constPrompt()));
    }

    @Test
    void addPromptRejectsInvalidName() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("1prompt", "desc", emptyArgs(), constPrompt()));
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my-prompt", "desc", emptyArgs(), constPrompt()));
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my prompt", "desc", emptyArgs(), constPrompt()));
    }

    @Test
    void addPromptRejectsNullDescription() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my_prompt", null, emptyArgs(), constPrompt()));
    }

    @Test
    void addPromptRejectsBlankDescription() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my_prompt", "  ", emptyArgs(), constPrompt()));
    }

    @Test
    void addPromptRejectsNullBuilder() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my_prompt", "desc", null, constPrompt()));
    }

    @Test
    void addPromptRejectsNullFunction() {
        MCPPromptHandler handler = new MCPPromptHandler();
        assertThrows(IllegalArgumentException.class, () ->
                handler.addPrompt("my_prompt", "desc", emptyArgs(), null));
    }

    @Test
    void addPromptDuplicateNameThrows() {
        MCPPromptHandler handler = new MCPPromptHandler();
        handler.addPrompt("greet", "desc", emptyArgs(), constPrompt());
        assertThrows(IllegalStateException.class, () ->
                handler.addPrompt("greet", "other desc", emptyArgs(), constPrompt()));
    }

    // ===== handlePromptsList =====

    private static JsonObject dispatch(java.util.function.Supplier<Object> action) {
        FakeHttpExchange exchange = new FakeHttpExchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}");
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        rpc.parsePost();
        rpc.sendResponse(action.get());
        return MCPProtocol.fromJson(exchange.getResponseBodyString(), JsonObject.class);
    }

    @Test
    void handlePromptsListEmpty() {
        MCPPromptHandler handler = new MCPPromptHandler();
        JsonObject body = dispatch(handler::handlePromptsList);
        assertEquals(0, body.getAsJsonObject("result").getAsJsonArray("prompts").size());
    }

    @Test
    void handlePromptsListReturnsRegisteredDescriptors() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("name", "Who to greet")
                .optional("greeting", "How to greet");
        handler.addPrompt("greet", "Greet someone", argSpec, constPrompt());

        JsonObject body = dispatch(handler::handlePromptsList);
        JsonArray prompts = body.getAsJsonObject("result").getAsJsonArray("prompts");
        assertEquals(1, prompts.size());

        JsonObject prompt = prompts.get(0).getAsJsonObject();
        assertEquals("greet", prompt.get("name").getAsString());
        assertEquals("Greet someone", prompt.get("description").getAsString());

        JsonArray args = prompt.getAsJsonArray("arguments");
        assertEquals(2, args.size());
        JsonObject a0 = args.get(0).getAsJsonObject();
        assertEquals("name", a0.get("name").getAsString());
        assertEquals("Who to greet", a0.get("description").getAsString());
        assertTrue(a0.get("required").getAsBoolean());
        JsonObject a1 = args.get(1).getAsJsonObject();
        assertEquals("greeting", a1.get("name").getAsString());
        assertFalse(a1.get("required").getAsBoolean());
    }

    @Test
    void handlePromptsListPreservesRegistrationOrder() {
        MCPPromptHandler handler = new MCPPromptHandler();
        handler.addPrompt("zulu", "Z", emptyArgs(), constPrompt());
        handler.addPrompt("alpha", "A", emptyArgs(), constPrompt());
        handler.addPrompt("mike", "M", emptyArgs(), constPrompt());

        JsonArray prompts = dispatch(handler::handlePromptsList)
                .getAsJsonObject("result").getAsJsonArray("prompts");
        assertEquals("zulu", prompts.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("alpha", prompts.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("mike", prompts.get(2).getAsJsonObject().get("name").getAsString());
    }

    // ===== handlePromptsGet =====

    private static MCPProtocol.JsonRpcRequest buildGetRequest(String promptName, Map<String, String> args) {
        MCPProtocol.GetPromptParams params = new MCPProtocol.GetPromptParams();
        params.setName(promptName);
        params.setArguments(args);
        MCPProtocol.JsonRpcRequest req = new MCPProtocol.JsonRpcRequest();
        req.setMethod("prompts/get");
        req.setId(1);
        req.setParams(MCPProtocol.gson().toJsonTree(params));
        return req;
    }

    private static JsonObject doGet(MCPPromptHandler handler, MCPProtocol.JsonRpcRequest req) {
        FakeHttpExchange exchange = new FakeHttpExchange(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/get\"}");
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        rpc.parsePost();
        rpc.sendResponse(handler.handlePromptsGet(req, Collections.emptyMap()));
        return MCPProtocol.fromJson(exchange.getResponseBodyString(), JsonObject.class);
    }

    @Test
    void handlePromptsGetReturnsFunctionResult() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("name", "Who");
        handler.addPrompt("greet", "Greet", argSpec, request -> {
            MCPProtocol.GetPromptResult r = new MCPProtocol.GetPromptResult();
            r.setDescription("Hi " + request.arguments().get("name"));
            MCPProtocol.PromptMessage msg = new MCPProtocol.PromptMessage();
            msg.setRole("user");
            msg.setContent(MCPProtocol.Content.text("Hello, " + request.arguments().get("name") + "!"));
            r.setMessages(List.of(msg));
            return r;
        });

        JsonObject body = doGet(handler, buildGetRequest("greet", Map.of("name", "Alice")));
        JsonObject result = body.getAsJsonObject("result");
        assertEquals("Hi Alice", result.get("description").getAsString());
        JsonArray messages = result.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("Hello, Alice!",
                messages.get(0).getAsJsonObject().getAsJsonObject("content").get("text").getAsString());
    }

    @Test
    void handlePromptsGetPassesArgsToFunction() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("a", "A")
                .optional("b", "B");
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> seen = new java.util.concurrent.atomic.AtomicReference<>();
        handler.addPrompt("cap", "capture", argSpec, request -> {
            seen.set(request.arguments());
            MCPProtocol.GetPromptResult r = new MCPProtocol.GetPromptResult();
            r.setMessages(Collections.emptyList());
            return r;
        });

        doGet(handler, buildGetRequest("cap", Map.of("a", "A-val", "b", "B-val")));
        assertEquals(Map.of("a", "A-val", "b", "B-val"), seen.get());
    }

    @Test
    void handlePromptsGetOmitsOptionalArgs() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("a", "A")
                .optional("b", "B");
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> seen = new java.util.concurrent.atomic.AtomicReference<>();
        handler.addPrompt("cap", "capture", argSpec, request -> {
            seen.set(request.arguments());
            MCPProtocol.GetPromptResult r = new MCPProtocol.GetPromptResult();
            r.setMessages(Collections.emptyList());
            return r;
        });

        doGet(handler, buildGetRequest("cap", Map.of("a", "only-required")));
        assertEquals(Map.of("a", "only-required"), seen.get());
    }

    @Test
    void handlePromptsGetUnknownPromptThrows() {
        MCPPromptHandler handler = new MCPPromptHandler();
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("nope", Map.of())));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertTrue(ex.getMessage().contains("nope"), ex.getMessage());
    }

    @Test
    void handlePromptsGetMissingRequiredArgThrows() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("name", "Who");
        handler.addPrompt("greet", "Greet", argSpec, constPrompt());
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("greet", Map.of())));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void handlePromptsGetUnknownArgBecomesInvalidParams() {
        MCPPromptHandler handler = new MCPPromptHandler();
        PromptArgumentsBuilder argSpec = new PromptArgumentsBuilder()
                .required("name", "Who");
        handler.addPrompt("greet", "Greet", argSpec, constPrompt());
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("greet", Map.of("name", "X", "stray", "Y"))));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertTrue(ex.getMessage().contains("stray"), ex.getMessage());
    }

    @Test
    void handlePromptsGetMissingNameParamThrows() {
        MCPPromptHandler handler = new MCPPromptHandler();
        MCPProtocol.JsonRpcRequest req = new MCPProtocol.JsonRpcRequest();
        req.setMethod("prompts/get");
        req.setId(1);
        // params without name
        MCPProtocol.GetPromptParams params = new MCPProtocol.GetPromptParams();
        req.setParams(MCPProtocol.gson().toJsonTree(params));

        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, req));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void handlePromptsGetNullResultThrowsInternalError() {
        MCPPromptHandler handler = new MCPPromptHandler();
        handler.addPrompt("nully", "null returner", emptyArgs(), request -> null);
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("nully", Map.of())));
        assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
    }

    @Test
    void handlePromptsGetFunctionExceptionBecomesInternalError() {
        MCPPromptHandler handler = new MCPPromptHandler();
        handler.addPrompt("boom", "throws", emptyArgs(), request -> {
            throw new RuntimeException("kaboom");
        });
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("boom", Map.of())));
        assertEquals(MCPServerException.INTERNAL_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    }

    @Test
    void handlePromptsGetPropagatesMCPServerException() {
        MCPPromptHandler handler = new MCPPromptHandler();
        handler.addPrompt("reject", "rejects", emptyArgs(), request -> {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS, "no");
        });
        MCPServerException ex = assertThrows(MCPServerException.class, () ->
                doGet(handler, buildGetRequest("reject", Map.of())));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("no", ex.getMessage());
    }
}
