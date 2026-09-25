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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The transport-agnostic MCP server: the tool, resource and prompt
 * registries, the session map, and the dispatch of {@code initialize} and
 * {@code ping}. It is the one object a caller configures, before handing it
 * to a single transport, which drives its lifecycle
 * (D_handler_transport_split):
 *
 * <pre>{@code
 * MCPHandler handler = new MCPHandler(serverInfo, null)
 *         .setAcceptNewSession(existing -> new SessionDecision.AcceptAndEvict(existing, EVICTION_REASON));
 * handler.addTool(descriptor, request -> MCPProtocol.Content.text("ok"));
 * new HttpMCPServer(0, "/mcp", handler).start();
 * }</pre>
 *
 * <p>It holds any number of sessions; the {@link #setAcceptNewSession}
 * policy caps them (D_session_policy_injected, D_supersede_sessions). The
 * listener setters lock once the first session is accepted
 * (D_settable_listeners), the registries once a transport starts.
 *
 * <p>Thread-safe once started; configuration belongs to one thread before
 * that. Over stdio there is one session per process (D_stdio_transport); over
 * HTTP a session is named by {@code Mcp-Session-Id}
 * (D_session_gate_two_stage).
 */
public class MCPHandler {

    private static final Logger LOG = Logger.getLogger(MCPHandler.class.getName());

    /**
     * {@link #negotiateProtocolVersion} echoes the client's version if it is
     * here, and answers {@link #LATEST_PROTOCOL_VERSION} otherwise.
     */
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    /** Offered to a client that asks for an unsupported version; it may then disconnect. */
    private static final String LATEST_PROTOCOL_VERSION = "2025-11-25";

    /**
     * A session idle this long is evicted, where the tick runs at all
     * ({@link #scheduleIdleCleanup()}). Tests backdate
     * {@link MCPSession#setLastAccessNanos(long)} instead of waiting.
     */
    static final long IDLE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(30);
    static final long CLEANUP_TICK_SECONDS = 60;

    private final MCPProtocol.Implementation serverInfo;
    private final @Nullable String instructions;

    /** Settable until {@link #firstSessionAccepted} (D_settable_listeners). */
    private volatile Function<List<MCPSession>, SessionDecision> acceptNewSession =
            existing -> new SessionDecision.Accept();
    private volatile Consumer<MCPSession> onSessionStarted = session -> {};
    private volatile Consumer<MCPSession> onSessionClosed = session -> {};
    /** Set under {@link #sessionGuardLock} as the first session enters the map. */
    private volatile boolean firstSessionAccepted = false;

    private final MCPToolHandler toolHandler = new MCPToolHandler();
    private final MCPResourceHandler resourceHandler = new MCPResourceHandler();
    private final MCPPromptHandler promptHandler = new MCPPromptHandler();

    private final ConcurrentHashMap<String, MCPSession> sessions = new ConcurrentHashMap<>();
    /** Guards all session-map mutations (put, remove, clear) to prevent races. */
    private final Object sessionGuardLock = new Object();

    static final String IDLE_REASON = "Session expired (idle timeout)";
    /**
     * Why each recently removed session id went — {@link #IDLE_REASON} or the
     * {@link SessionDecision.AcceptAndEvict#evictionReason()} — so its
     * client's 404 can say so (D_supersede_sessions). Older entries roll off.
     */
    private final BoundedLRUMap<String, String> tombstones = new BoundedLRUMap<>(64);

    private volatile boolean started = false;
    /** Lives from {@link #start()} to {@link #stop()}; see {@link #getExecutor()}. */
    private ScheduledExecutorService executor;

    /** The {@code serverInfo.name} the no-argument constructor advertises. */
    public static final String DEFAULT_SERVER_NAME = "TinyMCPServer";

    /**
     * This library's version, which the build writes to {@code version.properties} beside this
     * class; {@code "unknown"} when the sources were compiled without that step.
     */
    static final String VERSION = readVersion();

    private static String readVersion() {
        try (InputStream in = MCPHandler.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read version.properties", e);
            return "unknown";
        }
    }

    /**
     * Advertises {@code serverInfo} {@value #DEFAULT_SERVER_NAME} at this library's version, and
     * no instructions.
     */
    public MCPHandler() {
        this(new MCPProtocol.Implementation(DEFAULT_SERVER_NAME, VERSION), null);
    }

    /**
     * @param serverInfo   advertised in {@code initialize.serverInfo}
     * @param instructions advertised in {@code initialize.instructions};
     *                     {@code null} advertises none
     * @throws NullPointerException if {@code serverInfo}, or its name or version, is null — the
     *                              MCP schema requires both, and a client may drop a server
     *                              that omits them without saying why
     */
    public MCPHandler(MCPProtocol.Implementation serverInfo, @Nullable String instructions) {
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        Objects.requireNonNull(serverInfo.getName(), "serverInfo.name");
        Objects.requireNonNull(serverInfo.getVersion(), "serverInfo.version");
        this.instructions = instructions;
    }

    // ===== Session-lifecycle listener setters (D_settable_listeners, D_session_hooks) =====

    /**
     * Sets the admission policy run on every {@code initialize}; the default
     * accepts everything. See {@link SessionDecision} for what each answer
     * does.
     *
     * @param accept receives the live sessions, not including the new one; runs
     *               under the handler's guard lock, so keep it quick and free of
     *               side effects
     * @throws IllegalStateException if a session has already been accepted
     */
    public MCPHandler setAcceptNewSession(Function<List<MCPSession>, SessionDecision> accept) {
        Objects.requireNonNull(accept, "acceptNewSession");
        checkListenersUnlocked();
        this.acceptNewSession = accept;
        return this;
    }

    /**
     * Sets the listener run on each new session before its {@code initialize}
     * is answered, and after any sessions it supersedes have closed — the
     * place to allocate per-session state as attributes. If it throws, the
     * session is withdrawn and {@code initialize} fails.
     *
     * @throws IllegalStateException if a session has already been accepted
     */
    public MCPHandler setOnSessionStarted(Consumer<MCPSession> onStarted) {
        Objects.requireNonNull(onStarted, "onSessionStarted");
        checkListenersUnlocked();
        this.onSessionStarted = onStarted;
        return this;
    }

    /**
     * Sets the listener run once a session has left the map — on DELETE,
     * supersede, idle eviction or {@link #closeAllSessions()}. It holds the
     * session's own lock but not the handler's, so it may read attributes.
     *
     * @throws IllegalStateException if a session has already been accepted
     */
    public MCPHandler setOnSessionClosed(Consumer<MCPSession> onClosed) {
        Objects.requireNonNull(onClosed, "onSessionClosed");
        checkListenersUnlocked();
        this.onSessionClosed = onClosed;
        return this;
    }

    private void checkListenersUnlocked() {
        if (firstSessionAccepted) {
            throw new IllegalStateException(
                    "Session-lifecycle listeners are locked once the first session has been accepted");
        }
    }

    // ===== Registries =====

    /**
     * Registers a tool, advertised by {@code tools/list} and run by
     * {@code tools/call} synchronously on the dispatch thread.
     *
     * <pre>{@code
     * handler.addTool("echo", "Echo tool",
     *         new InputSchemaBuilder().requiredString("msg", "message").build(),
     *         request -> MCPProtocol.Content.text((String) request.arguments().raw().get("msg")));
     * }</pre>
     *
     * @apiNote how {@code tools/call} treats the arguments: an unknown tool is
     * {@code -32601}; a missing or {@code null} required argument is
     * {@code -32602}; an argument not in the schema is an {@code isError}
     * result naming the valid ones; a JSON number for an {@code integer}
     * parameter arrives as an {@code Integer}, and a fraction or a value past
     * 32 bits is {@code -32602}. Other numbers arrive as {@code Long} or
     * {@code Double}, and a quoted number as a {@code String}, which the
     * {@link Parameters} accessors coerce (D_coerce_string_numbers).
     *
     * @param name        must match {@code [a-zA-Z_][a-zA-Z0-9_]*}
     * @param description not blank
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} or {@code description}
     *                                  is blank, or {@code name} is malformed
     * @throws IllegalStateException    if the handler has been started, or a
     *                                  tool of that name is already registered
     */
    public void addTool(String name, String description, MCPProtocol.InputSchema inputSchema,
            ToolFunction function) {
        addTool(new ToolDescriptor(name, description, inputSchema), function);
    }

    /**
     * Registers a tool from a {@link ToolDescriptor}; otherwise as
     * {@link #addTool(String, String, MCPProtocol.InputSchema, ToolFunction)}.
     *
     * @throws NullPointerException  if {@code descriptor} or {@code function} is null
     * @throws IllegalStateException if the handler has been started, or a tool
     *                               of that name is already registered
     */
    public void addTool(ToolDescriptor descriptor, ToolFunction function) {
        if (started) {
            throw new IllegalStateException("Cannot add tools after server has been started");
        }
        toolHandler.addTool(descriptor, function);
    }

    /**
     * Registers a resource, advertised by {@code resources/list} and read by
     * {@code resources/read} under its exact {@code uri}; an unknown URI is
     * {@code -32602}.
     *
     * @param description may be null
     * @param mimeType    e.g. {@code "text/plain"}; may be null
     * @throws IllegalArgumentException if {@code uri}, {@code name}, or
     *                                  {@code function} is null or blank
     * @throws IllegalStateException    if the handler has been started, or a
     *                                  resource with that URI is already registered
     */
    public void addResource(String uri, String name, @Nullable String description,
            @Nullable String mimeType,
            ResourceFunction function) {
        if (started) {
            throw new IllegalStateException("Cannot add resources after server has been started");
        }
        resourceHandler.addResource(uri, name, description, mimeType, function);
    }

    /**
     * Registers a prompt, advertised by {@code prompts/list} and rendered by
     * {@code prompts/get}.
     *
     * @param name      must match {@code [a-zA-Z_][a-zA-Z0-9_]*}
     * @param arguments an empty builder for a prompt without arguments
     * @throws IllegalArgumentException if any argument is null or blank, or
     *                                  {@code name} is malformed
     * @throws IllegalStateException    if the handler has been started, or a
     *                                  prompt of that name is already registered
     */
    public void addPrompt(String name, String description, PromptArgumentsBuilder arguments,
            PromptFunction function) {
        if (started) {
            throw new IllegalStateException("Cannot add prompts after server has been started");
        }
        promptHandler.addPrompt(name, description, arguments, function);
    }

    // ===== Lifecycle =====

    /**
     * Freezes the registries and creates the shared executor; a second call
     * without {@link #stop()} leaks the first executor. Schedules no idle
     * cleanup — see {@link #scheduleIdleCleanup()}.
     */
    void start() {
        started = true;
        AtomicInteger threadNum = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "tiny-mcp-server-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedules the once-a-minute idle-cleanup tick, for a transport that
     * cannot see its client die; never for stdio (D_stdio_never_evicts).
     *
     * @throws IllegalStateException if {@link #start()} has not run
     */
    void scheduleIdleCleanup() {
        if (executor == null) {
            throw new IllegalStateException("start() must be called before scheduleIdleCleanup()");
        }
        executor.scheduleAtFixedRate(this::cleanupIdleSessionsSafe,
                CLEANUP_TICK_SECONDS, CLEANUP_TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** Shuts down the executor; sessions stay in the map. */
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Returns the one-thread daemon executor that also runs the idle tick, for
     * a tool's short background work — debouncing, deferred cleanup, brief
     * polling. Long-blocking work would starve the tick; give it an executor
     * of its own (D_idle_eviction).
     *
     * @throws IllegalStateException if the handler is not started
     */
    public ScheduledExecutorService getExecutor() {
        if (executor == null) {
            throw new IllegalStateException("Server not started");
        }
        return executor;
    }

    // ===== Session management =====

    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * @return the live session, or {@code null} — a caller that wants a 404
     * throws it itself, with {@link #tombstoneOrDefault}
     */
    @Nullable
    MCPSession getSession(String id) {
        Objects.requireNonNull(id, "id");
        return sessions.get(id);
    }

    /**
     * @return why the session left the map, or {@code null} if the id was never
     * a session, left without a tombstone, or its tombstone has rolled off
     */
    @Nullable
    String getTombstoneReason(String id) {
        Objects.requireNonNull(id, "id");
        return tombstones.get(id);
    }

    static final String SESSION_NOT_FOUND_MESSAGE = "Session not found.";

    /** Returns the 404 message for {@code id}: its tombstone reason, else {@link #SESSION_NOT_FOUND_MESSAGE}. */
    String tombstoneOrDefault(String id) {
        String reason = getTombstoneReason(id);
        return reason != null ? reason : SESSION_NOT_FOUND_MESSAGE;
    }

    /**
     * Removes a session without closing it or running {@code onSessionClosed};
     * that is the caller's to do, through {@link #notifySessionClosed}.
     *
     * @return the removed session, or {@code null} if no session had that id
     */
    @Nullable
    MCPSession removeSession(String id) {
        Objects.requireNonNull(id, "id");
        synchronized (sessionGuardLock) {
            return sessions.remove(id);
        }
    }

    /** {@link #removeSession} for every session at once. */
    List<MCPSession> removeAllSessions() {
        synchronized (sessionGuardLock) {
            List<MCPSession> closed = new ArrayList<>(sessions.values());
            sessions.clear();
            return closed;
        }
    }

    /**
     * Removes every session and runs {@code onSessionClosed} on each, for a
     * shutdown path such as stdio EOF. A throwing listener is logged and does
     * not stop the rest; a second call finds nothing to do.
     */
    public void closeAllSessions() {
        for (MCPSession s : removeAllSessions()) {
            try {
                notifySessionClosed(s);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onSessionClosed threw for " + s.getId(), e);
            }
        }
    }

    void notifySessionClosed(MCPSession session) {
        session.runListenerHook(() -> onSessionClosed.accept(session));
    }

    // ===== Protocol dispatch =====

    /**
     * What {@code initialize} produced: the new session's id, which HTTP
     * returns as {@code Mcp-Session-Id}, and the JSON-RPC result.
     *
     * <p>Immutable.
     */
    public static final class InitializeOutcome {

        private final String sessionId;
        private final MCPProtocol.InitializeResult result;

        public InitializeOutcome(String sessionId, MCPProtocol.InitializeResult result) {
            this.sessionId = sessionId;
            this.result = result;
        }

        public String sessionId() {
            return sessionId;
        }

        public MCPProtocol.InitializeResult result() {
            return result;
        }
    }

    /**
     * Admits a new session through the {@link #setAcceptNewSession} policy,
     * closes any it supersedes, runs {@code onSessionStarted}, and negotiates
     * the {@code initialize} result. The order, and why eviction runs outside
     * the guard lock: D_supersede_sessions.
     *
     * @throws MCPServerException HTTP 409 if the policy rejects
     * @throws RuntimeException   whatever {@code onSessionStarted} threw; the
     *                            session is withdrawn first
     */
    InitializeOutcome dispatchInitialize(MCPProtocol.JsonRpcRequest request) {
        MCPSession session;
        List<MCPSession> toEvict;
        synchronized (sessionGuardLock) {
            List<MCPSession> existingSnapshot = List.copyOf(sessions.values());
            SessionDecision decision = acceptNewSession.apply(existingSnapshot);
            if (decision instanceof SessionDecision.Reject) {
                LOG.warning("Rejecting initialization: acceptNewSession returned Reject");
                throw new MCPServerException(409,
                        MCPServerException.SERVER_NOT_INITIALIZED, "Another session is already active");
            }
            String evictionReason;
            if (decision instanceof SessionDecision.AcceptAndEvict) {
                final SessionDecision.AcceptAndEvict ae = (SessionDecision.AcceptAndEvict) decision;
                toEvict = ae.sessions();
                evictionReason = ae.evictionReason();
            } else {
                toEvict = List.of();
                evictionReason = null;
            }
            for (MCPSession e : toEvict) {
                tombstones.put(e.getId(), evictionReason);
            }
            String sessionId = UUID.randomUUID().toString();
            session = new MCPSession(sessionId, toolHandler, resourceHandler, promptHandler, this);
            sessions.put(sessionId, session);
            // D_settable_listeners: locks the listener setters.
            firstSessionAccepted = true;
        }

        // Evict outside the session-guard lock — close() blocks on the
        // session's lock, waiting for any in-flight request to finish.
        for (MCPSession e : toEvict) {
            try {
                e.close();
                boolean removed;
                synchronized (sessionGuardLock) {
                    removed = sessions.remove(e.getId(), e);
                }
                if (removed) {
                    LOG.info("Session superseded: " + e.getId());
                    notifySessionClosed(e);
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Failed to evict session " + e.getId(), ex);
            }
        }

        try {
            MCPSession s = session;
            session.runListenerHook(() -> onSessionStarted.accept(s));
        } catch (RuntimeException e) {
            // A half-initialized session must not stay in the map.
            LOG.log(Level.WARNING, "onSessionStarted threw for " + session.getId(), e);
            synchronized (sessionGuardLock) {
                sessions.remove(session.getId(), session);
            }
            throw e;
        }

        MCPProtocol.InitializeResult result = new MCPProtocol.InitializeResult();
        result.setProtocolVersion(negotiateProtocolVersion(request));
        result.setServerInfo(this.serverInfo);
        result.setInstructions(this.instructions);

        MCPProtocol.ServerCapabilities capabilities = new MCPProtocol.ServerCapabilities();
        capabilities.setTools(new MCPProtocol.ToolsCapability());
        capabilities.setResources(new MCPProtocol.ResourcesCapability());
        capabilities.setPrompts(new MCPProtocol.PromptsCapability());
        result.setCapabilities(capabilities);

        return new InitializeOutcome(session.getId(), result);
    }

    /**
     * Refuses an {@code MCP-Protocol-Version} header naming a version this server does not
     * speak; a missing header passes (D_protocol_version_header).
     *
     * @throws MCPServerException HTTP 400, listing the supported versions
     */
    static void requireSupportedProtocolVersion(@Nullable String version) {
        if (version == null || SUPPORTED_PROTOCOL_VERSIONS.contains(version)) {
            return;
        }
        throw new MCPServerException(400, MCPServerException.INVALID_REQUEST,
                "Unsupported MCP-Protocol-Version '" + version + "'. This server supports "
                        + String.join(", ", new TreeSet<>(SUPPORTED_PROTOCOL_VERSIONS))
                        + "; send the version negotiated at initialize.");
    }

    /** Answers {@code ping} with {@code {}}, without touching any session. */
    Object dispatchPing() {
        return Collections.emptyMap();
    }

    /**
     * Echoes the client's requested protocol version if supported, else offers
     * the latest, as the MCP {@code initialize} lifecycle prescribes.
     */
    private static String negotiateProtocolVersion(MCPProtocol.JsonRpcRequest request) {
        try {
            MCPProtocol.InitializeParams params = request.getParamsAs(MCPProtocol.InitializeParams.class);
            String requested = params != null ? params.getProtocolVersion() : null;
            if (requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) {
                return requested;
            }
        } catch (RuntimeException e) {
            // Malformed initialize params — fall back to the latest version.
            LOG.log(Level.FINE, "Could not parse initialize params", e);
        }
        return LATEST_PROTOCOL_VERSION;
    }

    // ===== Idle cleanup =====

    private void cleanupIdleSessionsSafe() {
        try {
            cleanupIdleSessions();
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Session cleanup tick failed", t);
        }
    }

    /**
     * One idle tick: evicts sessions idle past {@link #IDLE_TIMEOUT_NANOS},
     * skipping any with a request in flight until the next tick, and runs
     * {@code onSessionClosed} on each.
     */
    void cleanupIdleSessions() {
        long now = System.nanoTime();
        List<MCPSession> closed = new ArrayList<>();
        for (MCPSession s : sessions.values()) {
            if (now - s.getLastAccessNanos() < IDLE_TIMEOUT_NANOS) {
                continue;
            }
            if (!s.tryClose()) {
                continue;
            }
            synchronized (sessionGuardLock) {
                if (sessions.remove(s.getId(), s)) {
                    tombstones.put(s.getId(), IDLE_REASON);
                }
            }
            closed.add(s);
        }
        for (MCPSession s : closed) {
            LOG.info("Session expired (idle): " + s.getId());
            try {
                notifySessionClosed(s);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "onSessionClosed threw for " + s.getId(), e);
            }
        }
    }
}
