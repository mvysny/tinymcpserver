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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the raw arguments of one registered tool or prompt against its
 * declared parameters.
 *
 * <p>Immutable.
 */
class MCPParameterParser {

    /** Qualified identifier used in error messages, e.g. {@code tool 'echo'} or {@code prompt 'greet'}. */
    private final String name;
    private final Map<String, MCPProtocol.PropertySchema> properties;
    private final Set<String> required;

    MCPParameterParser(String toolName, MCPProtocol.InputSchema schema) {
        Objects.requireNonNull(schema, "schema");
        this.name = "tool '" + toolName + "'";
        this.properties = schema.getProperties() != null
                ? new LinkedHashMap<>(schema.getProperties())
                : new LinkedHashMap<>();
        this.required = schema.getRequired() != null
                ? new HashSet<>(schema.getRequired())
                : new HashSet<>();
    }

    /** Every prompt argument is a {@code string}; MCP prompts have no other type. */
    MCPParameterParser(String promptName, List<MCPProtocol.PromptArgument> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        this.name = "prompt '" + promptName + "'";
        this.properties = new LinkedHashMap<>();
        this.required = new HashSet<>();
        for (MCPProtocol.PromptArgument arg : arguments) {
            MCPProtocol.PropertySchema prop = new MCPProtocol.PropertySchema();
            prop.setType("string");
            prop.setDescription(arg.getDescription());
            this.properties.put(arg.getName(), prop);
            if (Boolean.TRUE.equals(arg.getRequired())) {
                this.required.add(arg.getName());
            }
        }
    }

    /**
     * Validates {@code rawArgs} and narrows an {@code integer} parameter's JSON
     * number to {@link Integer}. Every other value passes through as parsed,
     * a string-encoded number included ({@link Parameters} coerces that,
     * D_coerce_string_numbers).
     *
     * @return the declared parameters present, in declaration order; an absent
     *         optional one is omitted and a {@code null} value counts as absent
     * @throws MCPErrorResponseException for an unknown parameter, with a
     *                                   did-you-mean hint
     * @throws MCPServerException        with {@code INVALID_PARAMS} for a missing
     *                                   required parameter, or an {@code integer}
     *                                   one that is fractional or out of
     *                                   {@code int} range
     */
    Map<String, Object> parse(Map<String, Object> rawArgs) {
        List<String> unknownParams = new ArrayList<>();
        for (String key : rawArgs.keySet()) {
            if (!properties.containsKey(key)) {
                unknownParams.add(key);
            }
        }
        if (!unknownParams.isEmpty()) {
            StringBuilder msg = new StringBuilder("Unknown parameter");
            msg.append(unknownParams.size() == 1 ? " " : "s ");
            for (int i = 0; i < unknownParams.size(); i++) {
                if (i > 0) msg.append(", ");
                String unknown = unknownParams.get(i);
                msg.append("'").append(unknown).append("'");
                if (!properties.isEmpty()) {
                    String closest = null;
                    int bestDist = Integer.MAX_VALUE;
                    for (String known : properties.keySet()) {
                        int dist = ServerUtils.levenshteinDistance(unknown, known);
                        if (dist < bestDist) {
                            bestDist = dist;
                            closest = known;
                        }
                    }
                    int threshold = Math.max(unknown.length(), closest.length()) / 2;
                    if (bestDist <= threshold) {
                        msg.append(" (did you mean '").append(closest).append("'?)");
                    }
                }
            }
            msg.append(" for ").append(name).append(".");
            if (!properties.isEmpty()) {
                msg.append(" Valid parameters: ");
                int i = 0;
                for (String name : properties.keySet()) {
                    if (i++ > 0) msg.append(", ");
                    msg.append(name);
                }
            }

            throw new MCPErrorResponseException(msg.toString());
        }

        Map<String, Object> callArgs = new LinkedHashMap<>();
        for (Map.Entry<String, MCPProtocol.PropertySchema> entry : properties.entrySet()) {
            String paramName = entry.getKey();
            String paramType = entry.getValue().getType();
            Object value = rawArgs.get(paramName);
            boolean isRequired = required.contains(paramName);

            if (value == null) {
                if (isRequired) {
                    throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                            "Missing required parameter '" + paramName + "'");
                }
                continue;
            }

            if ("integer".equals(paramType)) {
                if (value instanceof Long) {
                    long l = (Long) value;
                    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                        throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                                "Parameter '" + paramName + "' value " + l + " is out of 32-bit integer range");
                    }
                    value = (int) l;
                } else if (value instanceof Double) {
                    double d = (Double) value;
                    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                        throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                                "Parameter '" + paramName + "' must be a whole number, got " + d);
                    }
                    long l = (long) d;
                    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                        throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                                "Parameter '" + paramName + "' value " + l + " is out of 32-bit integer range");
                    }
                    value = (int) l;
                }
            }

            callArgs.put(paramName, value);
        }
        return callArgs;
    }
}
