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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The resource registry behind {@link MCPHandler}, keyed by URI, and its
 * {@code resources/list} and {@code resources/read} dispatch. The listed
 * descriptor is fixed at registration; the contents come from the
 * {@link ResourceFunction} on every read.
 */
class MCPResourceHandler {

    private static final Logger LOG = Logger.getLogger(MCPResourceHandler.class.getName());

    private static class RegisteredResource {
        final ResourceFunction function;
        final MCPProtocol.Resource descriptor;

        RegisteredResource(String uri, String name, String description, String mimeType,
                ResourceFunction function) {
            this.function = function;
            this.descriptor = new MCPProtocol.Resource();
            this.descriptor.setUri(uri);
            this.descriptor.setName(name);
            this.descriptor.setDescription(description);
            this.descriptor.setMimeType(mimeType);
        }
    }

    private final Map<String, RegisteredResource> resources = new LinkedHashMap<>();

    /**
     * @param uri         the {@code resources/read} lookup key; not blank
     * @param name        not blank
     * @param description may be null
     * @param mimeType    may be null
     * @throws IllegalArgumentException if {@code uri}, {@code name}, or
     *                                  {@code function} is null/blank
     * @throws IllegalStateException    if a resource with the same URI is
     *                                  already registered
     */
    void addResource(String uri, String name, @Nullable String description,
            @Nullable String mimeType,
            ResourceFunction function) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Resource URI must not be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be null or blank");
        }
        if (function == null) {
            throw new IllegalArgumentException("ResourceFunction must not be null");
        }
        if (resources.containsKey(uri)) {
            throw new IllegalStateException("A resource with URI '" + uri + "' is already registered");
        }
        resources.put(uri, new RegisteredResource(uri, name, description, mimeType, function));
    }

    MCPProtocol.ListResourcesResult handleResourcesList() {
        MCPProtocol.ListResourcesResult result = new MCPProtocol.ListResourcesResult();
        List<MCPProtocol.Resource> descriptors = new ArrayList<>();
        for (RegisteredResource rr : resources.values()) {
            descriptors.add(rr.descriptor);
        }
        result.setResources(descriptors);
        return result;
    }

    /**
     * Dispatches {@code resources/read}. A resource has no {@code isError}
     * channel, so every failure is a JSON-RPC protocol error
     * (D_three_error_layers); an unknown URI is {@code INVALID_PARAMS}.
     */
    MCPProtocol.ReadResourceResult handleResourcesRead(MCPProtocol.JsonRpcRequest request,
            Map<String, String> transportHeaders) {
        MCPProtocol.ReadResourceParams params = request.getParamsAs(MCPProtocol.ReadResourceParams.class);
        if (params == null || params.getUri() == null || params.getUri().isBlank()) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Missing required parameter: uri");
        }
        String uri = params.getUri();
        RegisteredResource resource = resources.get(uri);
        if (resource == null) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Unknown resource: " + uri);
        }

        ResourceRequest req = new ResourceRequest(uri,
                transportHeaders,
                request.getMeta());
        List<MCPProtocol.ResourceContents> contents;
        try {
            contents = resource.function.call(req);
        } catch (MCPServerException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Resource '" + uri + "' threw an exception", e);
            throw new MCPServerException(MCPServerException.INTERNAL_ERROR,
                    "Resource '" + uri + "' failed: " + e);
        }
        if (contents == null) {
            throw new MCPServerException(MCPServerException.INTERNAL_ERROR,
                    "Resource '" + uri + "' returned null");
        }

        MCPProtocol.ReadResourceResult result = new MCPProtocol.ReadResourceResult();
        result.setContents(contents);
        return result;
    }
}
