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

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One client's MCP session, created by {@link MCPHandler} on
 * {@code initialize}: it dispatches that client's {@code tools/*},
 * {@code resources/*} and {@code prompts/*} requests and carries its
 * attributes. Tool code reaches it through {@link #getCurrent()}, here to
 * attach per-session state on first use:
 *
 * <pre>{@code
 * MCPSession session = MCPSession.getCurrent();
 * ToolContext context = session.getAttribute(ToolContext.class);
 * if (context == null) {
 *     context = new ToolContext(session.getHandler().getExecutor());
 *     session.setAttribute(ToolContext.class, context);
 * }
 * }</pre>
 *
 * <p>Thread-safe: a private lock serialises every request on the session.
 * The attribute accessors, though, must run while that lock is held — inside
 * a dispatched request or a lifecycle listener — or they throw
 * {@link IllegalStateException}.
 */
public class MCPSession {

    private final String id;
    private final MCPToolHandler toolHandler;
    private final MCPResourceHandler resourceHandler;
    private final MCPPromptHandler promptHandler;
    private final MCPHandler handler;
    private final Map<String, @Nullable Object> attributes = new HashMap<>();
    private final ReentrantLock sessionLock = new ReentrantLock();

    /** {@link System#nanoTime()} of the last dispatch; written under the lock, read without it. */
    private volatile long lastAccessNanos = System.nanoTime();

    /** Once set, every dispatch fails with a 404; written and checked under the lock. */
    private volatile boolean closed = false;

    MCPSession(String id, MCPToolHandler toolHandler, MCPResourceHandler resourceHandler,
            MCPPromptHandler promptHandler, MCPHandler handler) {
        this.id = Objects.requireNonNull(id, "id");
        this.toolHandler = Objects.requireNonNull(toolHandler, "toolHandler");
        this.resourceHandler = Objects.requireNonNull(resourceHandler, "resourceHandler");
        this.promptHandler = Objects.requireNonNull(promptHandler, "promptHandler");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** An opaque id; over HTTP it is the {@code Mcp-Session-Id} header value. */
    public String getId() {
        return id;
    }

    /**
     * Dispatches one session-scoped request under the session lock.
     *
     * @param transportHeaders the HTTP request headers; empty over stdio
     * @return the JSON-RPC {@code result} POJO, never {@code null}
     * @throws MCPServerException {@code -32601} for an unknown method, or a
     *                            404 if the session has been closed
     */
    Object handlePost(MCPProtocol.JsonRpcRequest request, Map<String, String> transportHeaders) {
        return runLocked(() -> doHandlePost(request, transportHeaders));
    }

    /**
     * @return {@code true} once closed; read without the session lock, so
     *         {@code false} is a hint, not a guarantee — the authoritative
     *         check is the one {@link #runLocked} makes while holding it
     */
    boolean isClosed() {
        return closed;
    }

    /**
     * Returns the {@link System#nanoTime()} at which the last request was
     * dispatched, or the session created; good for idle detection only, as
     * it is read without the lock.
     */
    long getLastAccessNanos() {
        return lastAccessNanos;
    }

    /** Test hook: backdates the last access so the idle tick fires without waiting. */
    void setLastAccessNanos(long nanos) {
        this.lastAccessNanos = nanos;
    }

    /**
     * Closes this session unless a request is in flight on it.
     *
     * @return {@code false} if a request holds the lock — the session is left
     *         open, for the next idle tick to retry (D_idle_eviction)
     */
    boolean tryClose() {
        if (!sessionLock.tryLock()) {
            return false;
        }
        try {
            closed = true;
        } finally {
            sessionLock.unlock();
        }
        return true;
    }

    /**
     * Closes this session, blocking until any in-flight request finishes —
     * so a superseded client's last call completes before
     * {@code onSessionClosed} releases its resources.
     */
    void close() {
        sessionLock.lock();
        try {
            closed = true;
        } finally {
            sessionLock.unlock();
        }
    }

    /** Returns the owning handler — for tool code, the way to {@link MCPHandler#getExecutor()}. */
    public MCPHandler getHandler() {
        return handler;
    }

    private Object doHandlePost(MCPProtocol.JsonRpcRequest request,
            Map<String, String> transportHeaders) {
        checkLocked();
        String rpcMethod = request.getMethod();
        switch (rpcMethod != null ? rpcMethod : "") {
            case "tools/list":
                return toolHandler.handleToolsList();
            case "tools/call":
                return toolHandler.handleToolsCall(request, transportHeaders);
            case "resources/list":
                return resourceHandler.handleResourcesList();
            case "resources/read":
                return resourceHandler.handleResourcesRead(request, transportHeaders);
            case "prompts/list":
                return promptHandler.handlePromptsList();
            case "prompts/get":
                return promptHandler.handlePromptsGet(request, transportHeaders);
            default:
                throw new MCPServerException(-32601, "Method not found: " + rpcMethod);
        }
    }

    private void checkLocked() {
        if (!sessionLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Invalid state: running outside of MCP session thread");
        }
    }

    /**
     * Returns session-scoped state a tool or listener stored earlier; it lives
     * as long as the session.
     *
     * @return the value, or {@code null} if none is set
     */
    public @Nullable Object getAttribute(String name) {
        checkLocked();
        Objects.requireNonNull(name, "name");
        return attributes.get(name);
    }

    /**
     * Stores session-scoped state, replacing any previous value.
     *
     * @param value {@code null} clears it
     */
    public void setAttribute(String name, @Nullable Object value) {
        checkLocked();
        Objects.requireNonNull(name, "name");
        attributes.put(name, value);
    }

    /**
     * {@link #getAttribute(String)} keyed by {@code type.getName()}.
     *
     * @throws ClassCastException if the string overload stored something else
     *                            under that name
     */
    public <T> @Nullable T getAttribute(Class<T> type) {
        checkLocked();
        return type.cast(getAttribute(type.getName()));
    }

    /**
     * {@link #setAttribute(String, Object)} keyed by {@code type.getName()} —
     * for the usual one instance of a type per session.
     */
    public <T> void setAttribute(Class<T> type, @Nullable T value) {
        checkLocked();
        setAttribute(type.getName(), value);
    }

    /** Bound by {@link #runLocked} and {@link #runListenerHook} for their duration. */
    private static final ThreadLocal<MCPSession> instance = new ThreadLocal<>();

    /**
     * Returns the session whose request or lifecycle listener is running on
     * this thread.
     *
     * @apiNote bound on the dispatch thread only: a tool that hands work to
     * another thread (a UI toolkit's event thread, say) resolves the session,
     * or the attribute it needs, first and captures it into the closure.
     * @throws NullPointerException if called outside a dispatched request or
     *                              listener
     */
    public static MCPSession getCurrent() {
        return Objects.requireNonNull(instance.get(), "Not running in a MCP session");
    }

    /**
     * Runs {@code block} as a dispatched request: under the session lock, with
     * {@link #getCurrent()} bound, and counting as activity for the idle tick.
     * The chokepoint every request passes through (D_idle_eviction).
     *
     * @throws MCPServerException a 404 carrying the tombstone reason, if the
     *                            session is closed
     */
    void runLocked(Runnable block) {
        runLocked(() -> {
            block.run();
            return null;
        });
    }

    /** {@link #runLocked(Runnable)}, returning what {@code block} returns. */
    <T extends @Nullable Object> T runLocked(Supplier<T> block) {
        sessionLock.lock();
        try {
            if (closed) {
                throw new MCPServerException(404,
                        MCPServerException.SERVER_NOT_INITIALIZED,
                        handler.tombstoneOrDefault(id));
            }
            lastAccessNanos = System.nanoTime();
            instance.set(this);
            try {
                return block.get();
            } finally {
                instance.remove();
            }
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * Runs a lifecycle listener under the session lock with
     * {@link #getCurrent()} bound, so it can use the attribute accessors as a
     * tool does. Unlike {@link #runLocked(Runnable)} it runs on a closed
     * session — {@code onSessionClosed} always does — and is not activity for
     * the idle tick.
     */
    void runListenerHook(Runnable block) {
        sessionLock.lock();
        try {
            instance.set(this);
            try {
                block.run();
            } finally {
                instance.remove();
            }
        } finally {
            sessionLock.unlock();
        }
    }
}
