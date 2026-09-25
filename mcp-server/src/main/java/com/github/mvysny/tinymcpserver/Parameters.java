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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tool call's arguments, read through typed accessors:
 *
 * <pre>{@code
 * int ref = params.getInt("ref");
 * }</pre>
 *
 * <p>Every accessor throws {@link MCPServerException} with
 * {@link MCPServerException#INVALID_PARAMS} naming the parameter when a
 * required one is missing or a value has the wrong type. The numeric ones
 * also take a string-encoded number, {@code "21"} for {@code 21}
 * (D_coerce_string_numbers).
 *
 * <p>Equality is over the raw map.
 */
public final class Parameters {

    private final Map<String, Object> raw;

    public Parameters(Map<String, Object> raw) {
        this.raw = Objects.requireNonNull(raw, "raw");
    }

    /** For passing the arguments on unchanged. */
    public Map<String, Object> raw() {
        return raw;
    }

    /** Returns a required string parameter. */
    public String getString(String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Required parameter '" + key + "' is missing");
        }
        if (!(value instanceof String)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be a string");
        }
        return (String) value;
    }

    /** Returns an optional string parameter, or {@code null} if absent. */
    @Nullable
    public String getStringOrNull(String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be a string");
        }
        return (String) value;
    }

    /** {@code false} for a fractional value, and for one {@link Number#intValue()} would truncate. */
    private static boolean isInt(Number num) {
        double d = num.doubleValue();
        return d % 1 == 0 && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    /** Returns a required integer parameter; a fractional or out-of-{@code int}-range value is rejected. */
    public int getInt(String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Required parameter '" + key + "' is missing");
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                        "Parameter '" + key + "' must be an integer, got '" + value + "'");
            }
        }
        if (!(value instanceof Number)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be an integer, got " + value.getClass().getSimpleName());
        }
        Number num = (Number) value;
        if (!isInt(num)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be an integer, got " + num);
        }
        return num.intValue();
    }

    /**
     * Returns a required numeric parameter as parsed, not narrowed to
     * {@code int}; a string-encoded one comes back as a {@link Double}.
     */
    public Number getNumber(String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Required parameter '" + key + "' is missing");
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                        "Parameter '" + key + "' must be a number, got '" + value + "'");
            }
        }
        if (!(value instanceof Number)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be a number, got " + value.getClass().getSimpleName());
        }
        return (Number) value;
    }

    /** Returns a required integer-array parameter; every element must be a whole number. */
    public List<Integer> getIntArray(String key) {
        Object value = raw.get(key);
        if (value == null) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Required parameter '" + key + "' is missing");
        }
        if (!(value instanceof List)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be an array of integers, got " + value.getClass().getSimpleName());
        }
        List<?> list = (List<?>) value;
        List<Integer> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Number num;
            if (element instanceof Number) {
                num = (Number) element;
            } else if (element instanceof String) {
                try {
                    num = Integer.parseInt((String) element);
                } catch (NumberFormatException e) {
                    throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                            "Parameter '" + key + "' must be an array of integers, but element at index " + i + " is '" + element + "'");
                }
            } else {
                throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                        "Parameter '" + key + "' must be an array of integers");
            }
            if (!isInt(num)) {
                throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                        "Parameter '" + key + "' must be an array of integers, but element at index " + i + " is " + num);
            }
            result.add(num.intValue());
        }
        return result;
    }

    /** Returns an optional integer-array parameter, or {@code null} if absent. */
    @Nullable
    public List<Integer> getIntArrayOrNull(String key) {
        if (!raw.containsKey(key)) {
            return null;
        }
        return getIntArray(key);
    }

    /** Returns an optional integer parameter, or {@code null} if absent. */
    @Nullable
    public Integer getIntOrNull(String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                        "Parameter '" + key + "' must be an integer, got '" + value + "'");
            }
        }
        if (!(value instanceof Number)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be an integer, got " + value.getClass().getSimpleName());
        }
        Number num = (Number) value;
        if (!isInt(num)) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be an integer, got " + num);
        }
        return num.intValue();
    }

    /**
     * Returns an optional boolean parameter, or {@code null} if absent. Takes the strings
     * {@code "true"} and {@code "false"} too (D_coerce_string_numbers).
     */
    @Nullable
    public Boolean getBooleanOrNull(String key) {
        Object value = raw.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        if (value instanceof String) {
            throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                    "Parameter '" + key + "' must be a boolean, got '" + value + "'");
        }
        throw new MCPServerException(MCPServerException.INVALID_PARAMS,
                "Parameter '" + key + "' must be a boolean, got " + value.getClass().getSimpleName());
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof Parameters)) return false;
        return raw.equals(((Parameters) o).raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }

    @Override
    public String toString() {
        return "Parameters" + raw;
    }
}
