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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.MCPServerException;
import com.github.mvysny.tinymcpserver.ToolRequest;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * {@link MCPClient} over the JDK's {@link HttpClient}, so it adds no runtime
 * dependency (D_no_framework_deps). See D_embedded_client.
 */
public final class TinyMCPClient implements MCPClient {

    private static final Logger LOG = Logger.getLogger(TinyMCPClient.class.getName());

    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String CLIENT_NAME = "tiny-mcp-client";
    private static final String CLIENT_VERSION = "1.0";

    private final URI serverUrl;
    private final HttpClient http;
    private final AtomicLong nextId = new AtomicLong(1);

    /** From the {@code initialize} response; {@code null} before it. */
    private volatile @Nullable String sessionId;

    /** The version the server picked in {@code initialize}; {@code null} before it. */
    private volatile @Nullable String negotiatedProtocolVersion;

    private volatile boolean closed;

    public TinyMCPClient(URI serverUrl) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.http = HttpClient.newHttpClient();
    }

    @Override
    public MCPProtocol.InitializeResult initialize() throws IOException {
        ensureOpen();

        MCPProtocol.InitializeParams params = new MCPProtocol.InitializeParams();
        params.setProtocolVersion(PROTOCOL_VERSION);
        params.setCapabilities(new MCPProtocol.ClientCapabilities());
        MCPProtocol.Implementation clientInfo = new MCPProtocol.Implementation();
        clientInfo.setName(CLIENT_NAME);
        clientInfo.setVersion(CLIENT_VERSION);
        params.setClientInfo(clientInfo);

        // Clear first, or the initialize request would carry the old session's headers.
        sessionId = null;
        negotiatedProtocolVersion = null;

        JsonElement resultEl = sendRequest("initialize", params, null);
        MCPProtocol.InitializeResult result = MCPProtocol.fromJson(resultEl.toString(),
                MCPProtocol.InitializeResult.class);
        negotiatedProtocolVersion = result.getProtocolVersion();

        sendNotification("notifications/initialized");

        return result;
    }

    @Override
    public List<MCPProtocol.Tool> listTools() throws IOException {
        ensureOpen();
        JsonElement resultEl = sendRequest("tools/list", null, null);
        MCPProtocol.ListToolsResult result = MCPProtocol.fromJson(resultEl.toString(),
                MCPProtocol.ListToolsResult.class);
        return result.getTools() != null ? result.getTools() : Collections.emptyList();
    }

    @Override
    public MCPProtocol.CallToolResult callTool(ToolRequest request) throws IOException {
        ensureOpen();
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Tool name must not be null or blank");
        }
        MCPProtocol.CallToolParams params = new MCPProtocol.CallToolParams();
        params.setName(request.name());
        params.setArguments(request.arguments() != null ? request.arguments().raw() : Collections.emptyMap());

        JsonElement resultEl = sendRequest("tools/call", params, request.jsonRpcMeta());
        return MCPProtocol.fromJson(resultEl.toString(), MCPProtocol.CallToolResult.class);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (sessionId == null) {
            return;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl).DELETE();
        addSessionHeaders(builder);
        try {
            HttpResponse<Void> response = http.send(builder.build(), BodyHandlers.discarding());
            int status = response.statusCode();
            if (status != 200 && status != 404) {
                // Best-effort cleanup: log, don't throw.
                LOG.warning("DELETE returned HTTP " + status + " for session " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending DELETE", e);
        } finally {
            sessionId = null;
        }
    }

    // ---------- internals ----------

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Client has been closed");
        }
    }

    /**
     * Adds {@code Mcp-Session-Id} and {@code MCP-Protocol-Version}
     * (R_mcp_protocol_version_header); each is omitted until
     * {@code initialize} has set it.
     */
    private void addSessionHeaders(HttpRequest.Builder builder) {
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (negotiatedProtocolVersion != null) {
            builder.header("MCP-Protocol-Version", negotiatedProtocolVersion);
        }
    }

    /**
     * Sends a JSON-RPC notification. Any 2xx succeeds (the server answers
     * 202 Accepted); 404 throws {@link MCPSessionLostException}.
     */
    private void sendNotification(String method) throws IOException {
        MCPProtocol.JsonRpcNotification notif = new MCPProtocol.JsonRpcNotification();
        notif.setMethod(method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl)
                .POST(BodyPublishers.ofString(notif.toJson()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        addSessionHeaders(builder);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending notification " + method, e);
        }
        int status = response.statusCode();
        if (status == 404) {
            throw new MCPSessionLostException(extractErrorMessage(response.body(),
                    "Session not found while sending notification " + method));
        }
        if (status / 100 != 2) {
            throw new MCPClientException(MCPServerException.INTERNAL_ERROR,
                    "Notification " + method + " failed: HTTP " + status + ": " + response.body());
        }
    }

    /**
     * Returns the {@code error.message} of a JSON-RPC error envelope, so a 404
     * carries the server's reason (such as the supersede tombstone,
     * D_supersede_sessions).
     *
     * @return {@code fallback} if {@code body} does not parse or has no
     *         non-empty message
     */
    private static String extractErrorMessage(String body, String fallback) {
        if (body == null || body.isEmpty()) return fallback;
        try {
            JsonElement el = MCPProtocol.fromJson(body, JsonElement.class);
            if (el == null || !el.isJsonObject()) return fallback;
            JsonElement err = el.getAsJsonObject().get("error");
            if (err == null || !err.isJsonObject()) return fallback;
            JsonElement msg = err.getAsJsonObject().get("message");
            if (msg == null || !msg.isJsonPrimitive()) return fallback;
            String s = msg.getAsString();
            return (s == null || s.isEmpty()) ? fallback : s;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Sends a JSON-RPC request and returns its {@code result}; captures any
     * {@code Mcp-Session-Id} response header.
     *
     * @param extraMeta set as {@code params._meta} when non-null
     */
    private JsonElement sendRequest(String method, Object params, JsonObject extraMeta) throws IOException {
        MCPProtocol.JsonRpcRequest request = new MCPProtocol.JsonRpcRequest();
        request.setId(nextId.getAndIncrement());
        request.setMethod(method);
        if (params != null) {
            request.setParamsFrom(params);
        }
        if (extraMeta != null) {
            JsonElement paramsEl = request.getParams();
            JsonObject paramsObj;
            if (paramsEl != null && paramsEl.isJsonObject()) {
                paramsObj = paramsEl.getAsJsonObject();
            } else {
                paramsObj = new JsonObject();
                request.setParams(paramsObj);
            }
            paramsObj.add("_meta", extraMeta);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(serverUrl)
                .POST(BodyPublishers.ofString(request.toJson()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        addSessionHeaders(builder);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending request " + method, e);
        }

        int status = response.statusCode();
        // Streamable HTTP may answer as SSE instead of plain JSON.
        String body = unframeSse(response.body(),
                response.headers().firstValue("Content-Type").orElse(null));

        // initialize carries no session id, so its 404 is not a lost session.
        if (status == 404 && !"initialize".equals(method)) {
            throw new MCPSessionLostException(extractErrorMessage(body,
                    "Session not found (HTTP 404) on " + method));
        }

        // Only initialize's response brings a new id; later ones repeat it.
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> this.sessionId = id);

        if (status / 100 != 2) {
            MCPProtocol.ErrorObject errObj = tryParseErrorBody(body);
            if (errObj != null) {
                throw new MCPClientException(errObj.getCode(), errObj.getMessage());
            }
            throw new MCPClientException(MCPServerException.INTERNAL_ERROR,
                    "HTTP " + status + ": " + (body != null ? body : ""));
        }

        JsonElement bodyEl;
        try {
            bodyEl = MCPProtocol.fromJson(body, JsonElement.class);
        } catch (JsonSyntaxException e) {
            throw new MCPClientException(MCPServerException.INTERNAL_ERROR,
                    "Malformed JSON in response: " + body, e);
        }
        if (bodyEl == null || !bodyEl.isJsonObject()) {
            throw new MCPClientException(MCPServerException.INTERNAL_ERROR,
                    "Expected JSON-RPC object in response: " + body);
        }
        JsonObject obj = bodyEl.getAsJsonObject();
        JsonElement errorEl = obj.get("error");
        if (errorEl != null && errorEl.isJsonObject()) {
            MCPProtocol.ErrorObject errObj = MCPProtocol.fromJson(errorEl.toString(),
                    MCPProtocol.ErrorObject.class);
            throw new MCPClientException(errObj.getCode(), errObj.getMessage());
        }
        JsonElement resultEl = obj.get("result");
        if (resultEl == null) {
            throw new MCPClientException(MCPServerException.INTERNAL_ERROR,
                    "Response has neither 'result' nor 'error': " + body);
        }
        return resultEl;
    }

    /**
     * Returns the data of the first SSE event in {@code body}, its
     * {@code data:} lines joined with {@code \n}; a non-SSE body comes back
     * unchanged.
     *
     * @implNote Reading only the first event assumes it is the response. A
     * server that streams {@code notifications/progress} first — as it may
     * once a caller sends a {@code progressToken} — would be misread; the fix
     * is to scan for the event whose id matches the request.
     */
    private static String unframeSse(String body, String contentType) {
        if (body == null || contentType == null
                || !contentType.toLowerCase().contains("text/event-stream")) {
            return body;
        }
        StringBuilder data = new StringBuilder();
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) value = value.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(value);
            } else if (line.isEmpty() && data.length() > 0) {
                return data.toString();
            }
            // id:, event: and : comment lines carry nothing we need.
        }
        return data.toString();
    }

    /**
     * @return the JSON-RPC {@code error} object of an HTTP error body, or
     *         {@code null} if it is blank, not JSON, or has none
     */
    private static MCPProtocol.@Nullable ErrorObject tryParseErrorBody(@Nullable String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement el = MCPProtocol.fromJson(body, JsonElement.class);
            if (el == null || !el.isJsonObject()) return null;
            JsonElement errorEl = el.getAsJsonObject().get("error");
            if (errorEl == null || !errorEl.isJsonObject()) return null;
            return MCPProtocol.fromJson(errorEl.toString(), MCPProtocol.ErrorObject.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
