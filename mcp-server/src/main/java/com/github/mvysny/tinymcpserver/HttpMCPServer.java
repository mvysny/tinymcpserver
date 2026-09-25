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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The HTTP transport: serves a configured {@link MCPHandler} over POST and
 * DELETE on {@code 127.0.0.1} (D_localhost_no_auth), routing each request to
 * its session by the {@code Mcp-Session-Id} header.
 *
 * <pre>{@code
 * MCPHandler handler = new MCPHandler()
 *         .setOnSessionClosed(s -> closedIds.add(s.getId()));
 * handler.addTool("echo", "Echo tool",
 *         new InputSchemaBuilder().requiredString("msg", "message").build(),
 *         request -> MCPProtocol.Content.text((String) request.arguments().raw().get("msg")));
 * HttpMCPServer server = new HttpMCPServer(0, "/mcp", handler);  // 0 = any free port
 * server.start();
 * URI uri = URI.create(server.getUrl());
 * }</pre>
 *
 * <p>Configure the handler completely before {@link #start()}; the server
 * adds no configuration of its own. Its threads are daemons, so a running
 * server does not keep the JVM alive.
 */
public class HttpMCPServer {

    private static final Logger LOG = Logger.getLogger(HttpMCPServer.class.getName());

    public static final int DEFAULT_PORT = 18088;
    public static final String DEFAULT_CONTEXT_PATH = "/mcp";

    /** As requested; {@code 0} asks the OS for an ephemeral port. */
    private final int port;
    private final String contextPath;
    private final MCPHandler handler;
    private HttpServer httpServer;
    /** The port actually bound by {@link #start()}; {@code -1} when not running. */
    private volatile int boundPort = -1;

    /** {@link #DEFAULT_PORT}, {@link #DEFAULT_CONTEXT_PATH}, and an empty handler. */
    public HttpMCPServer() {
        this(DEFAULT_PORT, DEFAULT_CONTEXT_PATH, new MCPHandler());
    }

    /** An empty handler, to be configured through {@link #getHandler()}. */
    public HttpMCPServer(int port, String contextPath) {
        this(port, contextPath, new MCPHandler());
    }

    /**
     * @param port        0–65535; {@code 0} lets the OS pick a free port, which
     *                    {@link #getPort()} reports after {@link #start()}
     * @param contextPath must start with a slash
     * @param handler     configured before {@link #start()}
     * @throws IllegalArgumentException if {@code port} is out of range or
     *                                  {@code contextPath} lacks the leading slash
     */
    public HttpMCPServer(int port, String contextPath, MCPHandler handler) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Parameter port: invalid value " + port + ": must be in [0, 65535]");
        }
        this.port = port;
        Objects.requireNonNull(contextPath, "contextPath");
        if (!contextPath.startsWith("/")) {
            throw new IllegalArgumentException("Parameter contextPath: invalid value " + contextPath + ": must start with a slash");
        }
        this.contextPath = contextPath;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public String getUrl() {
        return "http://127.0.0.1:" + getPort() + contextPath;
    }

    /**
     * Binds the port and starts serving; returns once the listener is up.
     *
     * @throws TransportIOException if the port cannot be bound
     */
    public void start() {
        try {
            httpServer = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        } catch (IOException e) {
            throw new TransportIOException("Failed to bind HTTP server on port " + port, e);
        }
        boundPort = httpServer.getAddress().getPort();
        httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        httpServer.createContext(contextPath, this::handleRequest);
        handler.start();
        // A remote client can vanish without sending DELETE, so HTTP — and
        // only HTTP — evicts on idle (D_stdio_never_evicts).
        handler.scheduleIdleCleanup();
        // Start from a daemon thread so that HTTP-Dispatcher inherits daemon status,
        // preventing it from keeping the JVM alive after the host application closes.
        Thread starter = new Thread(httpServer::start, "mcp-server-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("HttpMCPServer started on " + getUrl());
    }

    /**
     * Stops listening, then closes every live session, running
     * {@code onSessionClosed} on each, and stops the handler. Blocks until
     * each session's in-flight request, if any, finishes.
     */
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            boundPort = -1;
        }
        handler.closeAllSessions();
        handler.stop();
        LOG.info("HttpMCPServer stopped");
    }

    public MCPHandler getHandler() {
        return handler;
    }

    /**
     * Returns the bound port while running; otherwise the requested one, which
     * may be {@code 0}.
     */
    public int getPort() {
        return boundPort > 0 ? boundPort : port;
    }

    public String getContextPath() {
        return contextPath;
    }

    private void handleRequest(HttpExchange exchange) {
        JsonRpcExchange rpc = new JsonRpcExchange(exchange);
        try {
            String method = exchange.getRequestMethod();
            switch (method) {
                case "POST":
                    handlePost(rpc);
                    break;
                case "DELETE":
                    handleDelete(rpc);
                    break;
                default:
                    trySendPlain(rpc, 405, "Method Not Allowed");
                    break;
            }
        } catch (MCPServerException e) {
            LOG.log(Level.FINE, "Request produced MCP error (code=" + e.getCode() + ")", e);
            trySendError(rpc, e.getHttpStatus(), e.getCode(), e.getMessage());
        } catch (TransportIOException e) {
            // Transport is dead — no point trying to write another response.
            LOG.log(Level.WARNING, "Transport I/O failed; abandoning response", e);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error handling request", e);
            trySendError(rpc, 500, MCPServerException.INTERNAL_ERROR, "Internal error");
        }
    }

    private static void trySendError(JsonRpcExchange rpc, int httpStatus, int code, String message) {
        try {
            rpc.sendError(httpStatus, code, message);
        } catch (TransportIOException ioe) {
            LOG.log(Level.WARNING, "Could not send error response (transport dead)", ioe);
        }
    }

    private static void trySendPlain(JsonRpcExchange rpc, int status, String message) {
        try {
            rpc.sendPlain(status, message);
        } catch (TransportIOException ioe) {
            LOG.log(Level.WARNING, "Could not send plain response (transport dead)", ioe);
        }
    }

    private void handlePost(JsonRpcExchange rpc) {
        // D_session_gate_two_stage: a stale id is rejected before the body is read.
        String incomingSessionId = rpc.getHttpExchange().getRequestHeaders()
                .getFirst("Mcp-Session-Id");
        if (incomingSessionId != null && handler.getSession(incomingSessionId) == null) {
            LOG.warning("Rejecting request: unknown Mcp-Session-Id " + incomingSessionId);
            throw new MCPServerException(404,
                    MCPServerException.SERVER_NOT_INITIALIZED,
                    handler.tombstoneOrDefault(incomingSessionId));
        }

        MCPProtocol.JsonRpcRequest request = rpc.parsePost();
        if (request == null) return; // notification — 202 already sent

        String rpcMethod = request.getMethod();

        if ("initialize".equals(rpcMethod)) {
            MCPHandler.InitializeOutcome outcome = handler.dispatchInitialize(request);
            rpc.setSessionId(outcome.sessionId());
            rpc.sendResponse(outcome.result());
            return;
        }
        if ("ping".equals(rpcMethod)) {
            rpc.sendResponse(handler.dispatchPing());
            return;
        }

        if (incomingSessionId == null) {
            LOG.warning("Rejecting '" + rpcMethod + "': no Mcp-Session-Id header");
            throw new MCPServerException(400,
                    MCPServerException.SERVER_NOT_INITIALIZED,
                    "Server not initialized. Send 'initialize' first.");
        }
        MCPSession session = handler.getSession(incomingSessionId);
        if (session == null) {
            // Race: session removed between header check and lookup
            throw new MCPServerException(404,
                    MCPServerException.SERVER_NOT_INITIALIZED,
                    handler.tombstoneOrDefault(incomingSessionId));
        }

        // D_protocol_version_header: checked only past initialize, so negotiation is never refused.
        MCPHandler.requireSupportedProtocolVersion(
                rpc.getHttpExchange().getRequestHeaders().getFirst("MCP-Protocol-Version"));

        rpc.setSessionId(session.getId());
        Object result = session.handlePost(request, rpc.getTransportHeaders());
        rpc.sendResponse(result);
    }

    private void handleDelete(JsonRpcExchange rpc) {
        String incomingSessionId = rpc.getHttpExchange().getRequestHeaders()
                .getFirst("Mcp-Session-Id");
        List<MCPSession> closed;
        if (incomingSessionId != null) {
            MCPSession session = handler.removeSession(incomingSessionId);
            closed = session != null ? List.of(session) : List.of();
        } else {
            closed = handler.removeAllSessions();
        }
        for (MCPSession session : closed) {
            LOG.info("Session terminated: " + session.getId());
            handler.notifySessionClosed(session);
        }
        rpc.sendPlain(200, "");
    }

    /** Test hook: runs the idle-cleanup tick synchronously. */
    void cleanupIdleSessions() {
        handler.cleanupIdleSessions();
    }
}
