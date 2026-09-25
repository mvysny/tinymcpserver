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

/**
 * A JSON-RPC protocol error: a
 * <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0</a>
 * or <a href="https://modelcontextprotocol.io">MCP</a> error code plus the HTTP
 * status it rides on. See D_three_error_layers.
 */
public class MCPServerException extends RuntimeException {

    /** Invalid JSON was received by the server. */
    public static final int PARSE_ERROR = -32700;

    /** The JSON sent is not a valid Request object. */
    public static final int INVALID_REQUEST = -32600;

    /** The method does not exist or is not available. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** Invalid method parameter(s). */
    public static final int INVALID_PARAMS = -32602;

    /** Internal JSON-RPC error. */
    public static final int INTERNAL_ERROR = -32603;

    /** Server has not been initialized (implementation-defined server error). */
    public static final int SERVER_NOT_INITIALIZED = -32002;

    private final int httpStatus;
    private final int code;

    public MCPServerException(int code, String message) {
        this(200, code, message, null);
    }

    public MCPServerException(int code, String message, Throwable cause) {
        this(200, code, message, cause);
    }

    public MCPServerException(int httpStatus, int code, String message) {
        this(httpStatus, code, message, null);
    }

    public MCPServerException(int httpStatus, int code, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /**
     * @return {@code 200} unless constructed with another — a JSON-RPC error
     *         normally rides a 200; a session or request-shape failure uses a
     *         4xx. Ignored over stdio.
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }
}
