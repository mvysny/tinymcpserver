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

import com.github.mvysny.tinymcpserver.MCPServerException;

/**
 * The server answered HTTP 404 to a non-{@code initialize} call: the session
 * is gone — the server restarted, evicted it as idle, or a newer session
 * superseded it. The message is the server's reason when it sent one.
 * Recover by calling {@link MCPClient#initialize()} again; the client never
 * does that for you (D_no_auto_retry).
 */
public class MCPSessionLostException extends MCPClientException {

    public MCPSessionLostException(String message) {
        super(MCPServerException.SERVER_NOT_INITIALIZED, message);
    }

    public MCPSessionLostException(String message, Throwable cause) {
        super(MCPServerException.SERVER_NOT_INITIALIZED, message, cause);
    }
}
