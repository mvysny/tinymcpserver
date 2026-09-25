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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MCPSessionTest {

    private static MCPSession session;

    @BeforeAll
    static void setup() {
        MCPToolHandler toolHandler = new MCPToolHandler();
        toolHandler.addTool(new ToolDescriptor("echo", "Echo tool",
                        new InputSchemaBuilder().requiredString("msg", "message").build()),
                request -> MCPProtocol.Content.text((String) request.arguments().raw().get("msg")));
        session = new MCPSession("test-session-id", toolHandler, new MCPResourceHandler(),
                new MCPPromptHandler(), new MCPHandler());
    }

    // ===== Helpers =====

    private FakeHttpExchange dispatch(String jsonRpcBody) {
        FakeHttpExchange exchange = new FakeHttpExchange(jsonRpcBody);
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        MCPProtocol.JsonRpcRequest request = rpc.parsePost();
        assertNotNull(request, "parsePost should succeed for valid JSON-RPC");
        Object result = session.handlePost(request, rpc.getTransportHeaders());
        rpc.sendResponse(result);
        return exchange;
    }

    private FakeHttpExchange dispatch(String method, int id) {
        return dispatch("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\"}");
    }

    private static JsonObject parseResponse(FakeHttpExchange exchange) {
        return MCPProtocol.fromJson(exchange.getResponseBodyString(), JsonObject.class);
    }

    // ===== Tests =====

    @Test
    void resourcesListReturnsEmptyList() throws Exception {
        FakeHttpExchange exchange = dispatch("resources/list", 1);
        assertEquals(200, exchange.getResponseCode());
        JsonObject result = parseResponse(exchange).getAsJsonObject("result");
        assertNotNull(result);
        assertTrue(result.getAsJsonArray("resources").isEmpty());
    }

    @Test
    void promptsListReturnsEmptyList() throws Exception {
        FakeHttpExchange exchange = dispatch("prompts/list", 1);
        assertEquals(200, exchange.getResponseCode());
        JsonObject result = parseResponse(exchange).getAsJsonObject("result");
        assertNotNull(result);
        assertTrue(result.getAsJsonArray("prompts").isEmpty());
    }

    @Test
    void unknownMethodThrowsMethodNotFound() {
        MCPServerException ex = assertThrows(MCPServerException.class,
                () -> dispatch("nonexistent/method", 1));
        assertEquals(-32601, ex.getCode());
        assertTrue(ex.getMessage().contains("nonexistent/method"));
    }

    @Test
    void toolsListDelegatesToToolHandler() throws Exception {
        FakeHttpExchange exchange = dispatch("tools/list", 1);
        assertEquals(200, exchange.getResponseCode());
        JsonObject result = parseResponse(exchange).getAsJsonObject("result");
        assertNotNull(result);
        JsonArray tools = result.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void toolsCallDelegatesToToolHandler() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"msg\":\"hello\"}}}";
        FakeHttpExchange exchange = dispatch(body);
        assertEquals(200, exchange.getResponseCode());
        JsonObject result = parseResponse(exchange).getAsJsonObject("result");
        assertNotNull(result);
        JsonArray content = result.getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("hello", content.get(0).getAsJsonObject().get("text").getAsString());
    }

    // ===== Attributes =====

    private static MCPSession freshSession() {
        return new MCPSession("attr-session", new MCPToolHandler(), new MCPResourceHandler(),
                new MCPPromptHandler(), new MCPHandler());
    }

    @Test
    void getAttributeReturnsNullForMissingName() {
        MCPSession s = freshSession();
        s.runLocked(() -> assertNull(s.getAttribute("missing")));
    }

    @Test
    void setAttributeThenGetAttributeRoundtrips() {
        MCPSession s = freshSession();
        s.runLocked(() -> {
            s.setAttribute("key", "value");
            assertEquals("value", s.getAttribute("key"));
        });
    }

    @Test
    void setAttributeOverwritesPreviousValue() {
        MCPSession s = freshSession();
        s.runLocked(() -> {
            s.setAttribute("key", "first");
            s.setAttribute("key", "second");
            assertEquals("second", s.getAttribute("key"));
        });
    }

    @Test
    void setAttributeAllowsNullValue() {
        MCPSession s = freshSession();
        s.runLocked(() -> {
            s.setAttribute("key", "value");
            s.setAttribute("key", null);
            assertNull(s.getAttribute("key"));
        });
    }

    @Test
    void getAttributeRejectsNullName() {
        MCPSession s = freshSession();
        s.runLocked(() ->
                assertThrows(NullPointerException.class, () -> s.getAttribute((String) null)));
    }

    @Test
    void setAttributeRejectsNullName() {
        MCPSession s = freshSession();
        s.runLocked(() ->
                assertThrows(NullPointerException.class, () -> s.setAttribute((String) null, "v")));
    }

    @Test
    void attributesAreIsolatedPerSession() {
        MCPSession a = freshSession();
        MCPSession b = freshSession();
        a.runLocked(() -> a.setAttribute("key", "A-value"));
        b.runLocked(() -> assertNull(b.getAttribute("key")));
    }

    @Test
    void typedSetAttributeThenGetAttributeRoundtrips() {
        MCPSession s = freshSession();
        StringBuilder value = new StringBuilder("hello");
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class, value);
            assertSame(value, s.getAttribute(StringBuilder.class));
        });
    }

    @Test
    void typedGetAttributeReturnsNullWhenMissing() {
        MCPSession s = freshSession();
        s.runLocked(() -> assertNull(s.getAttribute(StringBuilder.class)));
    }

    @Test
    void typedAttributeKeyIsClassName() {
        MCPSession s = freshSession();
        StringBuilder value = new StringBuilder("hi");
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class, value);
            assertSame(value, s.getAttribute(StringBuilder.class.getName()));
        });
    }

    @Test
    void stringKeyedAttributeVisibleViaTypedGetWhenNameMatches() {
        MCPSession s = freshSession();
        StringBuilder value = new StringBuilder("hi");
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class.getName(), value);
            assertSame(value, s.getAttribute(StringBuilder.class));
        });
    }

    @Test
    void typedGetAttributeThrowsClassCastExceptionForWrongType() {
        MCPSession s = freshSession();
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class.getName(), "not a StringBuilder");
            assertThrows(ClassCastException.class, () -> s.getAttribute(StringBuilder.class));
        });
    }

    @Test
    void typedSetAttributeOverwritesSameTypeKey() {
        MCPSession s = freshSession();
        StringBuilder first = new StringBuilder("first");
        StringBuilder second = new StringBuilder("second");
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class, first);
            s.setAttribute(StringBuilder.class, second);
            assertSame(second, s.getAttribute(StringBuilder.class));
        });
    }

    @Test
    void typedAttributesForDifferentClassesDoNotCollide() {
        MCPSession s = freshSession();
        StringBuilder sb = new StringBuilder("sb");
        Integer i = 42;
        s.runLocked(() -> {
            s.setAttribute(StringBuilder.class, sb);
            s.setAttribute(Integer.class, i);
            assertSame(sb, s.getAttribute(StringBuilder.class));
            assertEquals(42, s.getAttribute(Integer.class));
        });
    }

    // ===== Lock enforcement =====

    @Test
    void getAttributeWithoutLockThrowsIllegalStateException() {
        MCPSession s = freshSession();
        assertThrows(IllegalStateException.class, () -> s.getAttribute("key"));
    }

    @Test
    void setAttributeWithoutLockThrowsIllegalStateException() {
        MCPSession s = freshSession();
        assertThrows(IllegalStateException.class, () -> s.setAttribute("key", "value"));
    }

    @Test
    void typedGetAttributeWithoutLockThrowsIllegalStateException() {
        MCPSession s = freshSession();
        assertThrows(IllegalStateException.class, () -> s.getAttribute(StringBuilder.class));
    }

    @Test
    void typedSetAttributeWithoutLockThrowsIllegalStateException() {
        MCPSession s = freshSession();
        assertThrows(IllegalStateException.class, () -> s.setAttribute(StringBuilder.class, new StringBuilder()));
    }

    @Test
    void runLockedReleasesLockAfterBlock() {
        MCPSession s = freshSession();
        s.runLocked(() -> s.setAttribute("key", "value"));
        assertThrows(IllegalStateException.class, () -> s.getAttribute("key"));
    }

    @Test
    void runLockedReleasesLockAfterBlockEvenOnFailure() throws Exception {
        MCPSession s = freshSession();
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> s.runLocked(() -> { throw boom; }));
        assertSame(boom, thrown);
        // From another thread: the lock is reentrant, so this one would get it even if it leaked.
        assertTrue(java.util.concurrent.CompletableFuture.supplyAsync(s::tryClose).get(),
                "the session lock must be free after the failed block");
    }

    // ===== getCurrent() / ThreadLocal binding =====

    @Test
    void getCurrentThrowsWhenNoSessionBound() {
        NullPointerException ex = assertThrows(NullPointerException.class, MCPSession::getCurrent);
        assertEquals("Not running in a MCP session", ex.getMessage());
    }

    @Test
    void getCurrentReturnsBoundSessionInsideRunLocked() {
        MCPSession s = freshSession();
        s.runLocked(() -> assertSame(s, MCPSession.getCurrent()));
    }

    @Test
    void getCurrentThrowsAfterRunLockedReturns() {
        MCPSession s = freshSession();
        s.runLocked(() -> assertSame(s, MCPSession.getCurrent()));
        assertThrows(NullPointerException.class, MCPSession::getCurrent);
    }

    @Test
    void getCurrentIsThreadLocal() throws Exception {
        MCPSession main = freshSession();
        MCPSession[] seenOnOther = new MCPSession[1];
        boolean[] otherSawNpeFirst = new boolean[1];
        Throwable[] err = new Throwable[1];
        main.runLocked(() -> {
            Thread t = new Thread(() -> {
                try {
                    try {
                        MCPSession.getCurrent();
                        err[0] = new AssertionError("expected NPE on unbound thread");
                        return;
                    } catch (NullPointerException expected) {
                        otherSawNpeFirst[0] = true;
                    }
                    MCPSession other = new MCPSession("other", new MCPToolHandler(),
                            new MCPResourceHandler(), new MCPPromptHandler(), new MCPHandler());
                    other.runLocked(() -> seenOnOther[0] = MCPSession.getCurrent());
                } catch (Throwable t2) {
                    err[0] = t2;
                }
            });
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertSame(main, MCPSession.getCurrent());
        });
        if (err[0] != null) throw new AssertionError(err[0]);
        assertTrue(otherSawNpeFirst[0], "other thread should have seen NPE before binding its own session");
        assertNotNull(seenOnOther[0]);
        assertEquals("other", seenOnOther[0].getId());
    }
}
