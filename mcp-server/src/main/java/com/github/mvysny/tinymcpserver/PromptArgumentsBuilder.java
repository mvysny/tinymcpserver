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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Fluent builder for a prompt's arguments — strings only, since MCP prompt
 * arguments have no other type (tools use {@link InputSchemaBuilder}).
 *
 * <pre>{@code
 * List<MCPProtocol.PromptArgument> args = new PromptArgumentsBuilder()
 *         .required("name", "Who to greet")
 *         .optional("style", "Greeting style")
 *         .build();
 * }</pre>
 */
public class PromptArgumentsBuilder {

    private final LinkedHashMap<String, MCPProtocol.PromptArgument> arguments = new LinkedHashMap<>();

    /**
     * @throws IllegalArgumentException if the name or description is
     *         null/blank, or the name doesn't match
     *         {@code [a-zA-Z_][a-zA-Z0-9_]*}
     * @throws IllegalStateException    if an argument with this name has
     *         already been added
     */
    public PromptArgumentsBuilder required(String name, String description) {
        return add(name, description, true);
    }

    /** Throws as {@link #required(String, String)} does. */
    public PromptArgumentsBuilder optional(String name, String description) {
        return add(name, description, false);
    }

    private PromptArgumentsBuilder add(String name, String description, boolean isRequired) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Argument name must not be null or blank");
        }
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Argument name must start with a letter or underscore and contain only alphanumeric characters and underscores: " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Argument description must not be null or blank");
        }
        if (arguments.containsKey(name)) {
            throw new IllegalStateException("Argument already exists: " + name);
        }
        MCPProtocol.PromptArgument arg = new MCPProtocol.PromptArgument();
        arg.setName(name);
        arg.setDescription(description);
        arg.setRequired(isRequired);
        arguments.put(name, arg);
        return this;
    }

    public List<MCPProtocol.PromptArgument> build() {
        return new ArrayList<>(arguments.values());
    }
}
