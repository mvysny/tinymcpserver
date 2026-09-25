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
 * What a {@link ResourceFunction} receives: the URI, the HTTP request headers
 * (empty over stdio, unmodifiable) and the request's {@code params._meta}.
 * See D_request_records.
 *
 * <p>Immutable.
 */
public final class ResourceRequest {

    private final String uri;
    private final Map<String, String> transportHeaders;
    private final @Nullable JsonObject jsonRpcMeta;

    /**
     * @param jsonRpcMeta the request's {@code params._meta}, or {@code null} if absent
     */
    public ResourceRequest(String uri,
                           Map<String, String> transportHeaders,
                           @Nullable JsonObject jsonRpcMeta) {
        this.uri = uri;
        this.transportHeaders = transportHeaders;
        this.jsonRpcMeta = jsonRpcMeta;
    }

    public String uri() {
        return uri;
    }

    public Map<String, String> transportHeaders() {
        return transportHeaders;
    }

    public @Nullable JsonObject jsonRpcMeta() {
        return jsonRpcMeta;
    }
}
