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

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An MCP tool's contract — name, description, input schema — as callers
 * declare and register it; {@link MCPProtocol.Tool} is the wire-shape POJO.
 *
 * <pre>{@code
 * new ToolDescriptor(
 *         "get_weather",
 *         "Current weather for a city. Call list_cities first for the names it knows.",
 *         new InputSchemaBuilder()
 *                 .requiredString("city", "The city name, as list_cities returns it")
 *                 .build());
 * }</pre>
 *
 * <p>Equality is structural over all three fields; schema equality is
 * {@link MCPProtocol.InputSchema#equals(Object)} — set semantics on
 * {@code required}, order-insensitive on {@code properties}, order-sensitive
 * on {@code enum}. See D_structural_schema_equality.
 *
 * <p>Immutable.
 */
public final class ToolDescriptor {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final String name;
    private final String description;
    private final MCPProtocol.InputSchema inputSchema;

    /**
     * @param name        tool name; must match {@code [a-zA-Z_][a-zA-Z0-9_]*}
     * @param description what the model reads; not blank
     * @param inputSchema usually built by
     *                    {@link InputSchemaBuilder}
     * @throws IllegalArgumentException if {@code name} is blank or malformed,
     *                                  or {@code description} is blank
     */
    public ToolDescriptor(String name,
                          String description,
                          MCPProtocol.InputSchema inputSchema) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Tool name must start with a letter or underscore and contain only alphanumeric characters and underscores: " + name);
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public MCPProtocol.InputSchema inputSchema() {
        return inputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolDescriptor)) {
            return false;
        }
        final ToolDescriptor that = (ToolDescriptor) o;
        return name.equals(that.name)
                && description.equals(that.description)
                && inputSchema.equals(that.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, inputSchema);
    }

    @Override
    public String toString() {
        return "ToolDescriptor[name=" + name + ", description=" + description
                + ", inputSchema=" + inputSchema + ']';
    }
}
