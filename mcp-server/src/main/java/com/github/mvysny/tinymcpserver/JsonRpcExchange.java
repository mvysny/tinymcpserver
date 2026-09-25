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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One HTTP request's JSON-RPC view: {@link #parsePost} reads the body and
 * remembers its id, and the {@code send*} methods write a response echoing
 * that id and, once {@link #setSessionId} has run, the {@code Mcp-Session-Id}
 * header.
 */
class JsonRpcExchange {

    private static final Logger LOG = Logger.getLogger(JsonRpcExchange.class.getName());
    private static final Gson GSON_WITH_NULLS = new GsonBuilder().serializeNulls().create();

    private final HttpExchange exchange;
    private @Nullable String sessionId;
    private @Nullable Object requestId;
    private boolean requestBodyConsumed;

    JsonRpcExchange(HttpExchange exchange) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    HttpExchange getHttpExchange() { return exchange; }
    void setSessionId(String sessionId) { this.sessionId = Objects.requireNonNull(sessionId, "sessionId"); }

    /**
     * Returns the request headers, unmodifiable, keeping only the first value
     * of a multi-valued header — MCP's are single-valued in practice.
     */
    Map<String, String> getTransportHeaders() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                result.put(entry.getKey(), values.get(0));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    void sendResponse(Object result) {
        MCPProtocol.JsonRpcResponse response = new MCPProtocol.JsonRpcResponse();
        response.setId(requestId);
        response.setResultFrom(result);
        sendJsonBody(200, response.toJson());
    }

    void sendError(int httpStatus, int code, String message) {
        MCPProtocol.ErrorObject errorObj = new MCPProtocol.ErrorObject();
        errorObj.setCode(code);
        errorObj.setMessage(message);

        MCPProtocol.JsonRpcError error = new MCPProtocol.JsonRpcError();
        error.setId(requestId);
        error.setError(errorObj);
        sendJsonBody(httpStatus, GSON_WITH_NULLS.toJson(error));
    }

    private String readBody() {
        try (InputStream is = exchange.getRequestBody()) {
            requestBodyConsumed = true;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TransportIOException(e);
        }
    }

    /**
     * Reads and discards anything left of the request body, so the connection
     * stays reusable.
     *
     * @implNote {@code com.sun.net.httpserver} closes the TCP connection when a
     * handler responds without consuming the request body, and the rejection
     * paths — an unknown session id, an unsupported method — answer before
     * reading it. The client's next request on that pooled keep-alive
     * connection dies with no response; Java 11's {@code HttpClient} does not
     * retry the POST (JDK 12+ does), so it fails with a flat "header parser
     * received no bytes".
     */
    private void drainRequestBody() {
        if (requestBodyConsumed) {
            return;
        }
        requestBodyConsumed = true;
        try (InputStream is = exchange.getRequestBody()) {
            final byte[] scratch = new byte[4096];
            while (is.read(scratch) >= 0) {
                // discard
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not drain request body before responding", e);
        }
    }

    /**
     * Reads the body as one JSON-RPC request and remembers its id for the
     * response.
     *
     * @return the parsed request, or {@code null} for a notification — the
     * 202 Accepted has already been sent, so the caller just returns
     * @throws MCPServerException HTTP 400 with {@code PARSE_ERROR} for
     * malformed JSON, or {@code INVALID_REQUEST} for a batch or a non-request
     * shape
     */
    MCPProtocol.@Nullable JsonRpcRequest parsePost() {
        String body = readBody();
        LOG.fine("Received POST: " + body);

        // Parse as a generic JsonElement first so we can distinguish
        // malformed JSON (-32700) from valid-JSON-but-wrong-shape (-32600).
        JsonElement jsonElement;
        try {
            jsonElement = MCPProtocol.fromJson(body, JsonElement.class);
        } catch (JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Malformed JSON in request", e);
            throw new MCPServerException(400, MCPServerException.PARSE_ERROR, "Parse error", e);
        }

        if (jsonElement instanceof JsonArray) {
            LOG.warning("Batch requests are not supported");
            throw new MCPServerException(400,
                    MCPServerException.INVALID_REQUEST, "Batch requests are not supported");
        }

        MCPProtocol.JsonRpcRequest request;
        try {
            request = MCPProtocol.gson().fromJson(jsonElement, MCPProtocol.JsonRpcRequest.class);
        } catch (JsonSyntaxException e) {
            LOG.log(Level.WARNING, "Invalid JSON-RPC request", e);
            throw new MCPServerException(400, MCPServerException.INVALID_REQUEST, "Invalid Request", e);
        }

        if (request.getId() == null) {
            sendPlain(202, "");
            return null;
        }

        requestId = request.getId();
        return request;
    }

    void sendPlain(int statusCode, String body) {
        drainRequestBody();
        try {
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(statusCode, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusCode, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        } catch (IOException e) {
            throw new TransportIOException(e);
        }
    }

    private void sendJsonBody(int statusCode, String json) {
        drainRequestBody();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (sessionId != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        }
        try {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            throw new TransportIOException(e);
        }
    }
}
