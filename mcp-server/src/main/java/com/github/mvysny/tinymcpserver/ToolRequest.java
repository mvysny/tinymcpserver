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

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * What a {@link ToolFunction} receives: the tool name (one function may serve
 * several), the arguments, the HTTP request headers (empty over stdio) and the
 * request's {@code params._meta}. See D_request_records.
 *
 * <p>Immutable.
 */
public final class ToolRequest {

    private final String name;
    private final Parameters arguments;
    private final Map<String, String> transportHeaders;
    private final @Nullable JsonObject jsonRpcMeta;

    /**
     * @param transportHeaders unmodifiable; empty in stdio mode
     * @param jsonRpcMeta      the request's {@code params._meta}, or {@code null} if absent
     */
    public ToolRequest(String name,
                       Parameters arguments,
                       Map<String, String> transportHeaders,
                       @Nullable JsonObject jsonRpcMeta) {
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.transportHeaders = Objects.requireNonNull(transportHeaders, "transportHeaders");
        this.jsonRpcMeta = jsonRpcMeta;
    }

    public ToolRequest(String name,
                       Map<String, Object> arguments,
                       Map<String, String> transportHeaders,
                       @Nullable JsonObject jsonRpcMeta) {
        this(name, new Parameters(arguments), transportHeaders, jsonRpcMeta);
    }

    public String name() {
        return name;
    }

    public Parameters arguments() {
        return arguments;
    }

    public Map<String, String> transportHeaders() {
        return transportHeaders;
    }

    public @Nullable JsonObject jsonRpcMeta() {
        return jsonRpcMeta;
    }
}
