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
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Protocol-version negotiation and the lookup/teardown helpers on
 * {@link MCPHandler} that the transports lean on.
 * Session-lifecycle listeners are covered by {@link MCPHandlerListenerTest}.
 */
class MCPHandlerDispatchTest {

    private static final String LATEST = "2025-11-25";

    // ===== Protocol version negotiation =====

    @Test
    void aSupportedVersionIsEchoedBack() {
        assertEquals("2025-06-18", initializeWith(new JsonPrimitive("2025-06-18"))
                .getProtocolVersion());
    }

    @Test
    void anUnsupportedVersionFallsBackToLatest() {
        // Answering with a version we do support lets the client decide
        // whether to proceed, rather than failing the handshake outright.
        assertEquals(LATEST, initializeWith(new JsonPrimitive("1999-01-01"))
                .getProtocolVersion());
    }

    @Test
    void anAbsentVersionFallsBackToLatest() {
        assertEquals(LATEST, initializeWith(null).getProtocolVersion());
    }

    @Test
    void malformedInitializeParamsFallBackToLatest() {
        // Unparseable params are not worth failing the handshake over.
        MCPHandler handler = new MCPHandler();
        MCPProtocol.JsonRpcRequest request = initializeRequest(null);
        request.setParams(new JsonPrimitive("nonsense"));

        MCPProtocol.InitializeResult result =
                (MCPProtocol.InitializeResult) handler.dispatchInitialize(request).result();
        assertEquals(LATEST, result.getProtocolVersion());
    }

    // ===== serverInfo =====

    @Test
    void theDefaultServerInfoNamesThisLibraryAtItsBuildVersion() {
        MCPProtocol.Implementation info = initializeWith(new JsonPrimitive(LATEST)).getServerInfo();
        assertEquals("TinyMCPServer " + System.getProperty("tinymcpserver.expectedVersion"),
                info.getName() + " " + info.getVersion());
    }

    @Test
    void aServerInfoWithoutANameOrVersionIsRefused() {
        assertThrows(NullPointerException.class,
                () -> new MCPHandler(new MCPProtocol.Implementation(null, "1"), null));
        assertThrows(NullPointerException.class,
                () -> new MCPHandler(new MCPProtocol.Implementation("n", null), null));
    }

    // ===== Session lookup =====

    @Test
    void lookupsRejectANullId() {
        MCPHandler handler = new MCPHandler();
        assertThrows(NullPointerException.class, () -> handler.getSession(null));
        assertThrows(NullPointerException.class, () -> handler.getTombstoneReason(null));
        assertThrows(NullPointerException.class, () -> handler.removeSession(null));
    }

    @Test
    void anUnknownIdHasNoTombstoneAndGetsTheGenericMessage() {
        MCPHandler handler = new MCPHandler();
        assertNull(handler.getTombstoneReason("never-existed"));
        assertEquals(MCPHandler.SESSION_NOT_FOUND_MESSAGE,
                handler.tombstoneOrDefault("never-existed"));
    }

    @Test
    void removeSessionReturnsTheRemovedSessionThenNull() {
        MCPHandler handler = new MCPHandler();
        String id = initializeOnce(handler);

        MCPSession removed = handler.removeSession(id);
        assertEquals(id, removed.getId());
        assertNull(handler.removeSession(id));
        assertEquals(0, handler.getSessionCount());
    }

    // ===== closeAllSessions =====

    @Test
    void closeAllSessionsEmptiesTheMapEvenWhenTheListenerThrows() {
        // A listener that blows up during teardown must not strand the remaining sessions.
        MCPHandler handler = new MCPHandler()
                .setOnSessionClosed(s -> { throw new IllegalStateException("listener blew up"); });
        initializeOnce(handler);

        handler.closeAllSessions();

        assertEquals(0, handler.getSessionCount());
    }

    @Test
    void closeAllSessionsOnAnEmptyHandlerIsANoOp() {
        new MCPHandler().closeAllSessions();
    }

    // ===== helpers =====

    private static MCPProtocol.InitializeResult initializeWith(JsonPrimitive protocolVersion) {
        MCPHandler handler = new MCPHandler();
        Object result = handler.dispatchInitialize(initializeRequest(protocolVersion)).result();
        return (MCPProtocol.InitializeResult) result;
    }

    private static String initializeOnce(MCPHandler handler) {
        MCPHandler.InitializeOutcome outcome =
                handler.dispatchInitialize(initializeRequest(new JsonPrimitive(LATEST)));
        assertSame(outcome.sessionId(), handler.getSession(outcome.sessionId()).getId());
        return outcome.sessionId();
    }

    private static MCPProtocol.JsonRpcRequest initializeRequest(JsonPrimitive protocolVersion) {
        MCPProtocol.JsonRpcRequest request = new MCPProtocol.JsonRpcRequest();
        request.setJsonrpc("2.0");
        request.setMethod("initialize");
        request.setId(1L);
        JsonObject params = new JsonObject();
        if (protocolVersion != null) {
            params.add("protocolVersion", protocolVersion);
        }
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "test");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);
        request.setParams(params);
        return request;
    }
}
