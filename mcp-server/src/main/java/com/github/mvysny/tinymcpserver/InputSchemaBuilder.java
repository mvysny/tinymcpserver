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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fluent builder for a tool's input schema; a {@code with*} call constrains
 * the parameter added just before it.
 *
 * <pre>{@code
 * new InputSchemaBuilder()
 *         .requiredString("city", "The city name, as list_cities returns it")
 *         .requiredInteger("offset", "0-based start index for paging").withMinimum(0)
 *         .requiredInteger("length", "Number of days to return").withMinimum(0)
 *         .build();
 * }</pre>
 *
 * <p>Parameter order is insertion order, in the built schema and in
 * {@link #toString()}; it is contractual. Every adder throws
 * {@link IllegalArgumentException} for a blank or malformed name
 * ({@code [a-zA-Z_][a-zA-Z0-9_]*}) or a blank description, and
 * {@link IllegalStateException} for a duplicate name.
 */
public class InputSchemaBuilder {

    private final LinkedHashMap<String, MCPProtocol.PropertySchema> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();
    private String lastAdded = null;

    public InputSchemaBuilder requiredString(String name, String description) {
        return add(name, "string", description, true);
    }

    public InputSchemaBuilder optionalString(String name, String description) {
        return add(name, "string", description, false);
    }

    public InputSchemaBuilder requiredInteger(String name, String description) {
        return add(name, "integer", description, true);
    }

    public InputSchemaBuilder optionalInteger(String name, String description) {
        return add(name, "integer", description, false);
    }

    public InputSchemaBuilder requiredNumber(String name, String description) {
        return add(name, "number", description, true);
    }

    public InputSchemaBuilder optionalNumber(String name, String description) {
        return add(name, "number", description, false);
    }

    public InputSchemaBuilder requiredBoolean(String name, String description) {
        return add(name, "boolean", description, true);
    }

    public InputSchemaBuilder optionalBoolean(String name, String description) {
        return add(name, "boolean", description, false);
    }

    public InputSchemaBuilder requiredArray(String name, String description) {
        return add(name, "array", description, true);
    }

    public InputSchemaBuilder optionalArray(String name, String description) {
        return add(name, "array", description, false);
    }

    public InputSchemaBuilder requiredObject(String name, String description) {
        return add(name, "object", description, true);
    }

    public InputSchemaBuilder optionalObject(String name, String description) {
        return add(name, "object", description, false);
    }

    private InputSchemaBuilder add(String name, String type, String description, boolean isRequired) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Parameter name must not be null or blank");
        }
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("Parameter name must start with a letter or underscore and contain only alphanumeric characters and underscores: " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Parameter description must not be null or blank");
        }
        if (properties.containsKey(name)) {
            throw new IllegalStateException("Parameter already exists: " + name);
        }
        MCPProtocol.PropertySchema schema = new MCPProtocol.PropertySchema();
        schema.setType(type);
        schema.setDescription(description);
        properties.put(name, schema);
        lastAdded = name;
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    /**
     * @throws IllegalArgumentException if {@code values} is empty
     * @throws IllegalStateException    if no parameter was added yet, or it
     *                                  already has an enum
     */
    public InputSchemaBuilder withEnum(String... values) {
        if (lastAdded == null) {
            throw new IllegalStateException("No parameter has been added yet");
        }
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Enum values must not be empty");
        }
        MCPProtocol.PropertySchema schema = properties.get(lastAdded);
        if (schema.getEnumValues() != null) {
            throw new IllegalStateException("Enum already set for parameter: " + lastAdded);
        }
        schema.setEnumValues(List.of(values));
        return this;
    }

    /**
     * @throws IllegalStateException if no parameter was added yet, or it
     *                               already has a minimum
     */
    public InputSchemaBuilder withMinimum(Number min) {
        if (lastAdded == null) {
            throw new IllegalStateException("No parameter has been added yet");
        }
        MCPProtocol.PropertySchema schema = properties.get(lastAdded);
        if (schema.getMinimum() != null) {
            throw new IllegalStateException("Minimum already set for parameter: " + lastAdded);
        }
        schema.setMinimum(min);
        return this;
    }

    /**
     * @throws IllegalStateException if no parameter was added yet, or it
     *                               already has a maximum
     */
    public InputSchemaBuilder withMaximum(Number max) {
        if (lastAdded == null) {
            throw new IllegalStateException("No parameter has been added yet");
        }
        MCPProtocol.PropertySchema schema = properties.get(lastAdded);
        if (schema.getMaximum() != null) {
            throw new IllegalStateException("Maximum already set for parameter: " + lastAdded);
        }
        schema.setMaximum(max);
        return this;
    }

    public MCPProtocol.InputSchema build() {
        MCPProtocol.InputSchema schema = new MCPProtocol.InputSchema();
        schema.setProperties(new LinkedHashMap<>(properties));
        schema.setRequired(new ArrayList<>(required));
        return schema;
    }

    /**
     * Renders the parameters without their descriptions:
     *
     * <pre>
     * ref: integer, page: integer[1,], count: integer?[,100], status: string(active|inactive|pending)
     * </pre>
     *
     * {@code ?} marks an optional parameter.
     */
    @Override
    public String toString() {
        return properties.entrySet().stream()
                .map(this::propertyToString)
                .collect(Collectors.joining(", "));
    }

    private String propertyToString(Map.Entry<String, MCPProtocol.PropertySchema> e) {
        String name = e.getKey();
        MCPProtocol.PropertySchema schema = e.getValue();
        String optionalMark = required.contains(name) ? "" : "?";
        String result = name + ": " + schema.getType() + optionalMark;
        if (schema.getEnumValues() != null) {
            result += "(" + String.join("|", schema.getEnumValues()) + ")";
        }
        if (schema.getMinimum() != null || schema.getMaximum() != null) {
            String min = schema.getMinimum() != null ? schema.getMinimum().toString() : "";
            String max = schema.getMaximum() != null ? schema.getMaximum().toString() : "";
            result += "[" + min + "," + max + "]";
        }
        return result;
    }
}
