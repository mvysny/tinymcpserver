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

/**
 * A tool's implementation, called synchronously on the thread serving
 * {@code tools/call}.
 *
 * <p>Arguments arrive validated, absent optional ones omitted, as plain Java
 * values — never GSON elements: {@code String}, {@code Boolean},
 * {@code List<Object>}, {@code Map<String, Object>}, and numbers as
 * {@code Long} or {@code Double} — except a declared {@code integer}, which
 * is an {@code Integer}. A string-encoded number stays a {@code String}; read
 * numbers through {@link Parameters}, which coerces it.
 */
@FunctionalInterface
public interface ToolFunction {
    /**
     * @return the one content item, or {@code null} for {@code "content": []}
     *         — valid MCP (R_mcp_empty_content)
     * @throws MCPErrorResponseException for {@code isError: true} with exactly its message
     * @throws MCPServerException        sent as the JSON-RPC error, as-is
     * @throws Exception                 anything else is {@code isError: true} with
     *                                   its {@link Throwable#toString()}
     */
    MCPProtocol.@Nullable Content call(ToolRequest request) throws Exception;
}
