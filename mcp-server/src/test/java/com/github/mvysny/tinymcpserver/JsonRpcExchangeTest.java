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
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chiefly the dead-socket paths: every {@link IOException} out of the
 * underlying exchange must surface as {@link TransportIOException}, so
 * {@code HttpMCPServer} abandons the response rather than writing another.
 */
class JsonRpcExchangeTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";

    // ===== Transport failures =====

    @Test
    void parsePostWrapsIoFailure() {
        IOException cause = new IOException("connection reset");
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange(PING).failIO(cause));

        TransportIOException ex = assertThrows(TransportIOException.class, rpc::parsePost);
        assertSame(cause, ex.getCause());
    }

    @Test
    void sendResponseWrapsIoFailure() {
        IOException cause = new IOException("broken pipe");
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange(PING).failIO(cause));

        TransportIOException ex = assertThrows(TransportIOException.class,
                () -> rpc.sendResponse(new JsonObject()));
        assertSame(cause, ex.getCause());
    }

    @Test
    void sendErrorWrapsIoFailure() {
        IOException cause = new IOException("broken pipe");
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange(PING).failIO(cause));

        assertThrows(TransportIOException.class,
                () -> rpc.sendError(500, MCPServerException.INTERNAL_ERROR, "boom"));
    }

    @Test
    void sendPlainWrapsIoFailure() {
        IOException cause = new IOException("client hung up");
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange(PING).failIO(cause));

        TransportIOException ex = assertThrows(TransportIOException.class,
                () -> rpc.sendPlain(405, "Method Not Allowed"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void sendPlainWithAnEmptyBodyWrapsIoFailure() {
        // The bodyless branch takes a different route to sendResponseHeaders.
        JsonRpcExchange rpc = new JsonRpcExchange(
                new FakeHttpExchange(PING).failIO(new IOException("client hung up")));

        assertThrows(TransportIOException.class, () -> rpc.sendPlain(202, ""));
    }

    // ===== Response shapes =====

    @Test
    void sendPlainWithAnEmptyBodySendsNoContent() {
        FakeHttpExchange exchange = new FakeHttpExchange(PING);
        new JsonRpcExchange(exchange).sendPlain(204, "");

        assertEquals(204, exchange.getResponseCode());
        assertEquals("", exchange.getResponseBodyString());
    }

    @Test
    void sessionIdIsEchoedOnJsonResponsesOnlyWhenSet() {
        FakeHttpExchange without = new FakeHttpExchange(PING);
        new JsonRpcExchange(without).sendResponse(new JsonObject());
        assertNull(without.getResponseHeaders().getFirst("Mcp-Session-Id"));

        FakeHttpExchange with = new FakeHttpExchange(PING);
        JsonRpcExchange rpc = new JsonRpcExchange(with);
        rpc.setSessionId("sess-42");
        rpc.sendResponse(new JsonObject());
        assertEquals("sess-42", with.getResponseHeaders().getFirst("Mcp-Session-Id"));
        assertEquals("application/json", with.getResponseHeaders().getFirst("Content-Type"));
    }

    @Test
    void transportHeadersTakeTheFirstValueOfEachHeader() {
        FakeHttpExchange exchange = new FakeHttpExchange(PING);
        exchange.getRequestHeaders().add("X-Trace", "first");
        exchange.getRequestHeaders().add("X-Trace", "second");

        var headers = new JsonRpcExchange(exchange).getTransportHeaders();

        // Headers normalises the key's casing on the way in ("X-trace").
        String key = exchange.getRequestHeaders().keySet().iterator().next();
        assertEquals("first", headers.get(key));
        assertThrows(UnsupportedOperationException.class, () -> headers.put(key, "third"));
    }

    // ===== parsePost =====

    @Test
    void notificationGets202AndNoParsedRequest() {
        FakeHttpExchange exchange = new FakeHttpExchange(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertNull(new JsonRpcExchange(exchange).parsePost());
        assertEquals(202, exchange.getResponseCode());
    }

    @Test
    void malformedJsonIsAParseErrorWithHttp400() {
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange("{not json"));

        MCPServerException ex = assertThrows(MCPServerException.class, rpc::parsePost);
        assertEquals(MCPServerException.PARSE_ERROR, ex.getCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void batchRequestIsAnInvalidRequestWithHttp400() {
        JsonRpcExchange rpc = new JsonRpcExchange(new FakeHttpExchange("[" + PING + "]"));

        MCPServerException ex = assertThrows(MCPServerException.class, rpc::parsePost);
        assertEquals(MCPServerException.INVALID_REQUEST, ex.getCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void parsePostReturnsTheRequestAndRemembersItsId() {
        FakeHttpExchange exchange = new FakeHttpExchange(PING);
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);

        MCPProtocol.JsonRpcRequest request = rpc.parsePost();
        assertEquals("ping", request.getMethod());

        rpc.sendResponse(new JsonObject());
        assertTrue(exchange.getResponseBodyString().contains("\"id\":7"),
                "the parsed id must ride the response; got: " + exchange.getResponseBodyString());
    }
}
