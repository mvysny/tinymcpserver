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

import java.util.List;
import java.util.Objects;

/**
 * What an {@link MCPHandler#setAcceptNewSession} policy returns for each
 * {@code initialize}, given the sessions already live. Single-session,
 * new-wins:
 *
 * <pre>{@code
 * new MCPHandler()
 *         .setAcceptNewSession(existing -> new SessionDecision.AcceptAndEvict(existing, EVICTION_REASON));
 * }</pre>
 *
 * <ul>
 *   <li>{@link Reject} — {@code initialize} fails with HTTP 409.</li>
 *   <li>{@link Accept} — admit it alongside the existing sessions.</li>
 *   <li>{@link AcceptAndEvict} — admit it and evict the listed sessions: each
 *       gets a tombstone carrying the {@code evictionReason}, so its client's
 *       next call fails with a 404 saying why, and then
 *       {@code onSessionClosed}.</li>
 * </ul>
 *
 * <p>Eviction runs outside the handler's guard lock and blocks until the
 * evicted session's in-flight request finishes, so {@code onSessionClosed}
 * sees a quiesced session. See D_supersede_sessions.
 *
 * <p>Immutable.
 *
 * @implNote the private constructor is what seals the hierarchy to the three
 * nested subclasses — only they can reach it. {@code sealed}/{@code permits}
 * would say so declaratively, but this module compiles at Java 11.
 */
public abstract class SessionDecision {

    private SessionDecision() {
    }

    /** Refuse the new session. */
    public static final class Reject extends SessionDecision {
        public Reject() {
        }
    }

    /** Accept the new session without evicting any existing session. */
    public static final class Accept extends SessionDecision {
        public Accept() {
        }
    }

    /**
     * Accept the new session and evict the listed sessions; an empty list
     * behaves like {@link Accept}.
     */
    public static final class AcceptAndEvict extends SessionDecision {

        private final List<MCPSession> sessions;
        private final String evictionReason;

        /**
         * @param sessions       copied defensively
         * @param evictionReason the displaced client's 404 message, verbatim — so
         *                       say, in the application's terms, why its session
         *                       closed and what the user should do
         * @throws IllegalArgumentException if {@code evictionReason} is blank
         */
        public AcceptAndEvict(List<MCPSession> sessions, String evictionReason) {
            Objects.requireNonNull(evictionReason, "evictionReason");
            if (evictionReason.isBlank()) {
                throw new IllegalArgumentException("evictionReason must not be blank");
            }
            this.sessions = List.copyOf(sessions);
            this.evictionReason = evictionReason;
        }

        public List<MCPSession> sessions() {
            return sessions;
        }

        public String evictionReason() {
            return evictionReason;
        }
    }
}
