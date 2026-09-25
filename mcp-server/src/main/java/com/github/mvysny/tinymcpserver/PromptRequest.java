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

/**
 * What a {@link PromptFunction} receives: the prompt name, its arguments, the
 * HTTP request headers (empty over stdio) and the request's
 * {@code params._meta}. Both maps are unmodifiable. See D_request_records.
 *
 * <p>Immutable.
 */
public final class PromptRequest {

    private final String name;
    private final Map<String, String> arguments;
    private final Map<String, String> transportHeaders;
    private final @Nullable JsonObject jsonRpcMeta;

    /**
     * @param jsonRpcMeta the request's {@code params._meta}, or {@code null} if absent
     */
    public PromptRequest(String name,
                         Map<String, String> arguments,
                         Map<String, String> transportHeaders,
                         @Nullable JsonObject jsonRpcMeta) {
        this.name = name;
        this.arguments = arguments;
        this.transportHeaders = transportHeaders;
        this.jsonRpcMeta = jsonRpcMeta;
    }

    public String name() {
        return name;
    }

    public Map<String, String> arguments() {
        return arguments;
    }

    public Map<String, String> transportHeaders() {
        return transportHeaders;
    }

    public @Nullable JsonObject jsonRpcMeta() {
        return jsonRpcMeta;
    }
}
