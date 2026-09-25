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
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.ToolRequest;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The caller side of the MCP HTTP transport: tools only (see D_embedded_client).
 *
 * <pre>{@code
 * try (MCPClient client = new TinyMCPClient(URI.create(server.getUrl()))) {
 *     client.initialize();
 *     MCPProtocol.CallToolResult result = client.callTool("get_forecast", Map.of("city", "Helsinki", "days", 3));
 * }
 * }</pre>
 *
 * <h2>Errors</h2>
 * <ul>
 *   <li>{@link MCPClientException} — a JSON-RPC error from the server, or an
 *       HTTP 4xx/5xx other than 404 rendered as a synthetic one.</li>
 *   <li>{@link MCPSessionLostException}, its subclass — HTTP 404 from a
 *       non-{@code initialize} call; recover by calling {@link #initialize()}
 *       again (D_no_auto_retry).</li>
 *   <li>{@link IOException} — transport failure; never retried.</li>
 * </ul>
 *
 * <p>A tool's own failure, {@link MCPProtocol.CallToolResult#getIsError()},
 * is a normal result, not an exception (D_three_error_layers).
 */
public interface MCPClient extends Closeable {

    /**
     * Performs the {@code initialize} handshake and sends
     * {@code notifications/initialized}. Calling again starts a fresh session
     * against the same URL.
     *
     * @throws MCPClientException if the server rejects the handshake
     * @throws IOException        if the transport fails
     */
    MCPProtocol.InitializeResult initialize() throws IOException;

    /**
     * Calls {@code tools/list}.
     *
     * @throws MCPSessionLostException if the session is gone
     * @throws MCPClientException      on any other protocol error
     * @throws IOException             if the transport fails
     */
    List<MCPProtocol.Tool> listTools() throws IOException;

    /**
     * Calls {@code tools/call} with the request's name, arguments and
     * {@code _meta} (sent as {@code params._meta}).
     *
     * <p>{@link ToolRequest#transportHeaders()} is <em>not</em> sent: those
     * are inbound headers, and forwarding them would clobber the outgoing
     * session and content-type headers.
     *
     * @throws MCPSessionLostException if the session is gone
     * @throws MCPClientException      on any other protocol error
     * @throws IOException             if the transport fails
     */
    MCPProtocol.CallToolResult callTool(ToolRequest request) throws IOException;

    /**
     * Calls the tool with no {@code _meta}.
     *
     * @param arguments pass {@link Map#of()} for none
     * @throws MCPSessionLostException if the session is gone
     * @throws MCPClientException      on any other protocol error
     * @throws IOException             if the transport fails
     */
    default MCPProtocol.CallToolResult callTool(String name, Map<String, Object> arguments) throws IOException {
        return callTool(name, arguments, null);
    }

    /**
     * Calls the tool with an explicit JSON-RPC {@code _meta}, such as a
     * {@code progressToken}.
     *
     * @param arguments pass {@link Map#of()} for none
     * @param meta      {@code null} for none
     * @throws MCPSessionLostException if the session is gone
     * @throws MCPClientException      on any other protocol error
     * @throws IOException             if the transport fails
     */
    default MCPProtocol.CallToolResult callTool(String name, Map<String, Object> arguments,
            @Nullable JsonObject meta) throws IOException {
        return callTool(new ToolRequest(name, arguments, Collections.emptyMap(), meta));
    }

    /**
     * Releases the session server-side with an HTTP {@code DELETE}; a 404
     * (already gone) is not an error. Idempotent; the client is unusable
     * afterwards.
     *
     * @throws IOException if the transport fails
     */
    @Override
    void close() throws IOException;
}
