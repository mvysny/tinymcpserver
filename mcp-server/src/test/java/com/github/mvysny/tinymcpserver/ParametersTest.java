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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParametersTest {

    // ── getString ────────────────────────────────────────────────────────────

    @Test
    void getStringReturnsValue() {
        var params = new Parameters(Map.of("name", "Alice"));
        assertEquals("Alice", params.getString("name"));
    }

    @Test
    void getStringThrowsWhenMissing() {
        var params = new Parameters(Map.of());
        var ex = assertThrows(MCPServerException.class, () -> params.getString("name"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Required parameter 'name' is missing", ex.getMessage());
    }

    @Test
    void getStringThrowsWhenWrongType() {
        var params = new Parameters(Map.of("name", 42));
        var ex = assertThrows(MCPServerException.class, () -> params.getString("name"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'name' must be a string", ex.getMessage());
    }

    // ── getStringOrNull ──────────────────────────────────────────────────────

    @Test
    void getStringOrNullReturnsValue() {
        var params = new Parameters(Map.of("name", "Bob"));
        assertEquals("Bob", params.getStringOrNull("name"));
    }

    @Test
    void getStringOrNullReturnsNullWhenMissing() {
        var params = new Parameters(Map.of());
        assertNull(params.getStringOrNull("name"));
    }

    @Test
    void getStringOrNullThrowsWhenWrongType() {
        var params = new Parameters(Map.of("name", 3.14));
        var ex = assertThrows(MCPServerException.class, () -> params.getStringOrNull("name"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'name' must be a string", ex.getMessage());
    }

    // ── getInt ───────────────────────────────────────────────────────────────

    @Test
    void getIntReturnsValueFromInteger() {
        var params = new Parameters(Map.of("ref", 7));
        assertEquals(7, params.getInt("ref"));
    }

    @Test
    void getIntReturnsValueFromDouble() {
        var params = new Parameters(Map.of("ref", 3.0));
        assertEquals(3, params.getInt("ref"));
    }

    @Test
    void getIntThrowsWhenOutOfIntRange() {
        var params = new Parameters(Map.of("ref", 3_000_000_000L));
        var ex = assertThrows(MCPServerException.class, () -> params.getInt("ref"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'ref' must be an integer, got 3000000000", ex.getMessage());
    }

    @Test
    void getIntThrowsWhenMissing() {
        var params = new Parameters(Map.of());
        var ex = assertThrows(MCPServerException.class, () -> params.getInt("ref"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Required parameter 'ref' is missing", ex.getMessage());
    }

    @Test
    void getIntReturnsValueFromString() {
        var params = new Parameters(Map.of("ref", "21"));
        assertEquals(21, params.getInt("ref"));
    }

    @Test
    void getIntThrowsWhenNonNumericString() {
        var params = new Parameters(Map.of("ref", "notanumber"));
        var ex = assertThrows(MCPServerException.class, () -> params.getInt("ref"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'ref' must be an integer, got 'notanumber'", ex.getMessage());
    }

    @Test
    void getIntThrowsWhenWrongType() {
        var params = new Parameters(Map.of("ref", List.of()));
        var ex = assertThrows(MCPServerException.class, () -> params.getInt("ref"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'ref' must be an integer, got ListN", ex.getMessage());
    }

    @Test
    void getIntThrowsWhenFractional() {
        var params = new Parameters(Map.of("ref", 3.7));
        var ex = assertThrows(MCPServerException.class, () -> params.getInt("ref"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'ref' must be an integer, got 3.7", ex.getMessage());
    }

    // ── getNumber ────────────────────────────────────────────────────────────

    @Test
    void getNumberReturnsRawNumber() {
        var params = new Parameters(Map.of("value", 42.5));
        Number result = params.getNumber("value");
        assertInstanceOf(Double.class, result);
        assertEquals(42.5, result.doubleValue());
    }

    @Test
    void getNumberThrowsWhenMissing() {
        var params = new Parameters(Map.of());
        var ex = assertThrows(MCPServerException.class, () -> params.getNumber("value"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Required parameter 'value' is missing", ex.getMessage());
    }

    @Test
    void getNumberReturnsValueFromString() {
        var params = new Parameters(Map.of("value", "42.5"));
        Number result = params.getNumber("value");
        assertEquals(42.5, result.doubleValue());
    }

    @Test
    void getNumberThrowsWhenNonNumericString() {
        var params = new Parameters(Map.of("value", "notanumber"));
        var ex = assertThrows(MCPServerException.class, () -> params.getNumber("value"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'value' must be a number, got 'notanumber'", ex.getMessage());
    }

    @Test
    void getNumberThrowsWhenWrongType() {
        var params = new Parameters(Map.of("value", List.of()));
        var ex = assertThrows(MCPServerException.class, () -> params.getNumber("value"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'value' must be a number, got ListN", ex.getMessage());
    }

    // ── getIntOrNull ─────────────────────────────────────────────────────────

    @Test
    void getIntOrNullReturnsValueFromInteger() {
        var params = new Parameters(Map.of("offset", 5));
        assertEquals(5, params.getIntOrNull("offset"));
    }

    @Test
    void getIntOrNullReturnsValueFromDouble() {
        var params = new Parameters(Map.of("offset", 10.0));
        assertEquals(10, params.getIntOrNull("offset"));
    }

    @Test
    void getIntOrNullReturnsNullWhenMissing() {
        var params = new Parameters(Map.of());
        assertNull(params.getIntOrNull("offset"));
    }

    @Test
    void getIntOrNullReturnsValueFromString() {
        var params = new Parameters(Map.of("offset", "5"));
        assertEquals(5, params.getIntOrNull("offset"));
    }

    @Test
    void getIntOrNullThrowsWhenNonNumericString() {
        var params = new Parameters(Map.of("offset", "five"));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntOrNull("offset"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'offset' must be an integer, got 'five'", ex.getMessage());
    }

    @Test
    void getIntOrNullThrowsWhenWrongType() {
        var params = new Parameters(Map.of("offset", List.of()));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntOrNull("offset"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'offset' must be an integer, got ListN", ex.getMessage());
    }

    @Test
    void getIntOrNullThrowsWhenFractional() {
        var params = new Parameters(Map.of("offset", 10.5));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntOrNull("offset"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'offset' must be an integer, got 10.5", ex.getMessage());
    }

    // ── getBooleanOrNull ─────────────────────────────────────────────────────

    @Test
    void getBooleanOrNullReturnsValueFromBoolean() {
        var params = new Parameters(Map.of("all_refs", true));
        assertEquals(true, params.getBooleanOrNull("all_refs"));
    }

    @Test
    void getBooleanOrNullReturnsNullWhenMissing() {
        var params = new Parameters(Map.of());
        assertNull(params.getBooleanOrNull("all_refs"));
    }

    @Test
    void getBooleanOrNullReturnsValueFromString() {
        assertEquals(true, new Parameters(Map.of("all_refs", "true")).getBooleanOrNull("all_refs"));
        assertEquals(false, new Parameters(Map.of("all_refs", "false")).getBooleanOrNull("all_refs"));
    }

    @Test
    void getBooleanOrNullThrowsWhenOtherString() {
        var params = new Parameters(Map.of("all_refs", "yes"));
        var ex = assertThrows(MCPServerException.class, () -> params.getBooleanOrNull("all_refs"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'all_refs' must be a boolean, got 'yes'", ex.getMessage());
    }

    @Test
    void getBooleanOrNullThrowsWhenWrongType() {
        var params = new Parameters(Map.of("all_refs", 1.0));
        var ex = assertThrows(MCPServerException.class, () -> params.getBooleanOrNull("all_refs"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'all_refs' must be a boolean, got Double", ex.getMessage());
    }

    // ── getIntArray ──────────────────────────────────────────────────────────

    @Test
    void getIntArrayReturnsValuesFromDoubles() {
        // A client that writes whole numbers as 1.0 delivers Doubles.
        var params = new Parameters(Map.of("indices", List.of(1.0, 2.0, 3.0)));
        assertEquals(List.of(1, 2, 3), params.getIntArray("indices"));
    }

    @Test
    void getIntArrayReturnsValuesFromIntegers() {
        var params = new Parameters(Map.of("indices", List.of(0, 5, 10)));
        assertEquals(List.of(0, 5, 10), params.getIntArray("indices"));
    }

    @Test
    void getIntArrayReturnsEmptyListForEmptyArray() {
        var params = new Parameters(Map.of("indices", List.of()));
        assertEquals(List.of(), params.getIntArray("indices"));
    }

    @Test
    void getIntArrayThrowsWhenMissing() {
        var params = new Parameters(Map.of());
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Required parameter 'indices' is missing", ex.getMessage());
    }

    @Test
    void getIntArrayThrowsWhenNotAList() {
        var params = new Parameters(Map.of("indices", "notalist"));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'indices' must be an array of integers, got String", ex.getMessage());
    }

    @Test
    void getIntArrayReturnsValuesFromStrings() {
        var params = new Parameters(Map.of("indices", List.of("1", "2", "3")));
        assertEquals(List.of(1, 2, 3), params.getIntArray("indices"));
    }

    @Test
    void getIntArrayThrowsWhenElementIsNonNumericString() {
        var params = new Parameters(Map.of("indices", List.of(1.0, "two", 3.0)));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'indices' must be an array of integers, but element at index 1 is 'two'", ex.getMessage());
    }

    @Test
    void getIntArrayThrowsWhenElementNotANumber() {
        var params = new Parameters(Map.of("indices", List.of(1.0, true, 3.0)));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'indices' must be an array of integers", ex.getMessage());
    }

    @Test
    void getIntArrayThrowsWhenElementIsFractional() {
        var params = new Parameters(Map.of("indices", List.of(1.0, 2.7, 3.0)));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'indices' must be an array of integers, but element at index 1 is 2.7", ex.getMessage());
    }

    @Test
    void getIntArrayThrowsWhenScalarNumber() {
        var params = new Parameters(Map.of("indices", 42));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArray("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
        assertEquals("Parameter 'indices' must be an array of integers, got Integer", ex.getMessage());
    }

    // ── getIntArrayOrNull ────────────────────────────────────────────────────

    @Test
    void getIntArrayOrNullReturnsValues() {
        var params = new Parameters(Map.of("indices", List.of(1.0, 2.0)));
        assertEquals(List.of(1, 2), params.getIntArrayOrNull("indices"));
    }

    @Test
    void getIntArrayOrNullReturnsNullWhenMissing() {
        var params = new Parameters(Map.of());
        assertNull(params.getIntArrayOrNull("indices"));
    }

    @Test
    void getIntArrayOrNullDistinguishesAbsentFromEmpty() {
        // An empty selection is a value; "no selection given" is not.
        var params = new Parameters(Map.of("indices", List.of()));
        assertEquals(List.of(), params.getIntArrayOrNull("indices"));
    }

    @Test
    void getIntArrayOrNullThrowsWhenPresentButNotAList() {
        var params = new Parameters(Map.of("indices", "1,2,3"));
        var ex = assertThrows(MCPServerException.class, () -> params.getIntArrayOrNull("indices"));
        assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void getIntArrayOrNullThrowsWhenAnElementIsFractional() {
        var params = new Parameters(Map.of("indices", List.of(1.0, 2.5)));
        assertThrows(MCPServerException.class, () -> params.getIntArrayOrNull("indices"));
    }

    // ── value semantics ──────────────────────────────────────────────────────

    @Test
    void equalsAndHashCodeFollowTheUnderlyingMap() {
        var a = new Parameters(Map.of("key", "value"));
        var b = new Parameters(new HashMap<>(Map.of("key", "value")));
        var other = new Parameters(Map.of("key", "different"));

        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, other);
        assertNotEquals(a, "not a Parameters");
        assertNotEquals(a, null);
    }

    @Test
    void toStringShowsTheArguments() {
        assertEquals("Parameters{key=value}", new Parameters(Map.of("key", "value")).toString());
    }

    // ── empty map ────────────────────────────────────────────────────────────

    @Test
    void aNullMapIsRejected() {
        assertThrows(NullPointerException.class, () -> new Parameters(null));
    }

    @Test
    void anEmptyMapHasNoParameters() {
        var params = new Parameters(Map.of());
        assertNull(params.getStringOrNull("key"));
        assertNull(params.getIntOrNull("key"));
        assertThrows(MCPServerException.class, () -> params.getString("key"));
        assertThrows(MCPServerException.class, () -> params.getInt("key"));
    }

    // ── explicit null value in map ───────────────────────────────────────────

    @Test
    void explicitNullValueTreatedAsMissing() {
        var map = new HashMap<String, Object>();
        map.put("key", null);
        var params = new Parameters(map);
        assertNull(params.getStringOrNull("key"));
        assertNull(params.getIntOrNull("key"));
        assertThrows(MCPServerException.class, () -> params.getString("key"));
        assertThrows(MCPServerException.class, () -> params.getInt("key"));
    }
}
