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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The stdio transport: serves a configured {@link MCPHandler} as
 * newline-delimited JSON-RPC, for a process an MCP client spawns as its
 * subprocess (D_stdio_transport).
 *
 * <pre>{@code
 * new StdioMCPServer(handler).runStdio(System.in, System.out);  // blocks until stdin EOF
 * }</pre>
 *
 * <p>One run per instance. The process holds exactly one session, which is
 * never idle-evicted (D_stdio_never_evicts).
 */
public class StdioMCPServer {

    private static final Logger LOG = Logger.getLogger(StdioMCPServer.class.getName());

    /**
     * For error envelopes: JSON-RPC 2.0 requires {@code "id": null} when the
     * request's id could not be parsed, and default GSON drops nulls.
     */
    private static final Gson GSON_WITH_NULLS = new GsonBuilder().serializeNulls().create();

    private final MCPHandler handler;

    /**
     * Set by {@code initialize}; a second {@code initialize} replaces it and
     * removes the old one from the handler's session map.
     */
    private MCPSession currentSession;

    /** The only writer to {@code out}; see {@link #runStdio} for the stdout capture. */
    private BufferedWriter writer;

    /** An empty handler, to be configured through {@link #getHandler()}. */
    public StdioMCPServer() {
        this(new MCPHandler());
    }

    /**
     * @param handler configured before {@link #runStdio}; its admission policy
     *                always sees an empty session list, because a
     *                re-{@code initialize} drops the old session first
     */
    public StdioMCPServer(MCPHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public MCPHandler getHandler() {
        return handler;
    }

    /**
     * Serves one JSON-RPC message per line of {@code in}, answering on
     * {@code out}, until {@code in} reaches EOF; then closes every session,
     * running {@code onSessionClosed}, and stops the handler.
     * <p>
     * When {@code out} is {@code System.out}, {@code System.out} is re-pointed
     * at {@code System.err} for good, so a stray {@code println} from a tool
     * or library cannot corrupt the framing (D_stdio_transport).
     * <p>
     * Protocol errors answer with a JSON-RPC error envelope and the loop goes
     * on; notifications get no answer. A read failure ends the loop as EOF
     * does.
     *
     * @throws IllegalArgumentException if {@code in} or {@code out} is null
     * @throws TransportIOException     if writing to {@code out} fails
     */
    public void runStdio(InputStream in, OutputStream out) {
        if (in == null || out == null) {
            throw new IllegalArgumentException("in and out must not be null");
        }
        if (out == System.out) {
            // The writer below keeps the real stdout. Never restored: the
            // process exits when stdin closes (D_stdio_transport).
            System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        }
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

        handler.start();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handleMessage(line);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Stdio input I/O failed; exiting read loop", e);
        } finally {
            // handler.stop() alone only shuts the executor down; release the
            // per-session resources before it goes.
            handler.closeAllSessions();
            handler.stop();
            currentSession = null;
        }
    }

    private void handleMessage(String line) {
        Object requestId = null;
        try {
            JsonElement element;
            try {
                element = MCPProtocol.fromJson(line, JsonElement.class);
            } catch (JsonSyntaxException e) {
                throw new MCPServerException(MCPServerException.PARSE_ERROR, "Parse error", e);
            }
            if (element instanceof JsonArray) {
                throw new MCPServerException(MCPServerException.INVALID_REQUEST,
                        "Batch requests are not supported");
            }

            MCPProtocol.JsonRpcRequest request;
            try {
                request = MCPProtocol.gson().fromJson(element, MCPProtocol.JsonRpcRequest.class);
            } catch (JsonSyntaxException e) {
                throw new MCPServerException(MCPServerException.INVALID_REQUEST, "Invalid Request", e);
            }

            if (request.getId() == null) {
                LOG.fine("Received notification: " + request.getMethod());
                return;
            }
            requestId = request.getId();

            Object result = dispatch(request);
            writeResponse(requestId, result);
        } catch (MCPServerException e) {
            LOG.log(Level.FINE, "Request produced MCP error (code=" + e.getCode() + ")", e);
            writeError(requestId, e.getCode(), e.getMessage());
        } catch (TransportIOException e) {
            // Ahead of the RuntimeException catch: the wire is dead, so leave
            // runStdio rather than try to write an error frame.
            LOG.log(Level.WARNING, "Stdio output I/O failed", e);
            throw e;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error handling stdio message", e);
            writeError(requestId, MCPServerException.INTERNAL_ERROR, "Internal error");
        }
    }

    private Object dispatch(MCPProtocol.JsonRpcRequest request) {
        String method = request.getMethod();
        if ("initialize".equals(method)) {
            // Replace, not accumulate: a re-initialize would otherwise
            // orphan the old session in the handler's map.
            if (currentSession != null) {
                MCPSession old = currentSession;
                currentSession = null;
                handler.removeSession(old.getId());
                old.close();
                try {
                    handler.notifySessionClosed(old);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "onSessionClosed threw for " + old.getId(), e);
                }
            }
            MCPHandler.InitializeOutcome outcome = handler.dispatchInitialize(request);
            currentSession = handler.getSession(outcome.sessionId());
            return outcome.result();
        }
        if ("ping".equals(method)) {
            return handler.dispatchPing();
        }
        if (currentSession == null) {
            throw new MCPServerException(MCPServerException.SERVER_NOT_INITIALIZED,
                    "Server not initialized. Send 'initialize' first.");
        }
        if (currentSession.isClosed()) {
            // Unreachable but for a shutdown-hook race at JVM teardown. Why
            // an IllegalStateException, not an assert or an Error:
            // D_stdio_never_evicts.
            throw new IllegalStateException("Stdio session " + currentSession.getId()
                    + " is closed but the read loop is still dispatching. A stdio session must"
                    + " live as long as its process; see D_stdio_never_evicts.");
        }
        return currentSession.handlePost(request, Collections.emptyMap());
    }

    private void writeResponse(Object requestId, Object result) {
        MCPProtocol.JsonRpcResponse response = new MCPProtocol.JsonRpcResponse();
        response.setId(requestId);
        response.setResultFrom(result);
        writeLine(response.toJson());
    }

    private void writeError(Object requestId, int code, String message) {
        MCPProtocol.ErrorObject errorObj = new MCPProtocol.ErrorObject();
        errorObj.setCode(code);
        errorObj.setMessage(message);

        MCPProtocol.JsonRpcError error = new MCPProtocol.JsonRpcError();
        error.setId(requestId);
        error.setError(errorObj);
        writeLine(GSON_WITH_NULLS.toJson(error));
    }

    private void writeLine(String json) {
        try {
            writer.write(json);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new TransportIOException(e);
        }
    }
}
