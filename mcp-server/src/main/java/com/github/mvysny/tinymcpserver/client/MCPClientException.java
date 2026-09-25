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

/**
 * A JSON-RPC error the server returned, with its {@link #getCode() code} and
 * message. An HTTP 4xx/5xx without a JSON-RPC error body, or a malformed
 * response, becomes a synthetic one with
 * {@link com.github.mvysny.tinymcpserver.MCPServerException#INTERNAL_ERROR}.
 * Transport failures are {@link java.io.IOException} instead.
 */
public class MCPClientException extends RuntimeException {

    private final int code;

    public MCPClientException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MCPClientException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
