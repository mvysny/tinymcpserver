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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MCPParameterParserTest {

    // ===== helpers =====

    private static MCPParameterParser parser(String toolName, InputSchemaBuilder builder) {
        return new MCPParameterParser(toolName, builder.build());
    }

    private static MCPParameterParser stringTool() {
        return parser("test_tool", new InputSchemaBuilder()
                .requiredString("message", "The message"));
    }

    private static MCPParameterParser intTool() {
        return parser("test_tool", new InputSchemaBuilder()
                .requiredInteger("count", "A count"));
    }

    /** Like {@code Map.of}, but admits {@code null} values. */
    private static Map<String, Object> mapWithNulls(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // ===== happy-path parsing =====

    @Nested
    class HappyPath {

        @Test
        void emptySchemaAcceptsEmptyArgs() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder());
            Map<String, Object> result = p.parse(Map.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void requiredStringParsed() {
            Map<String, Object> result = stringTool().parse(Map.of("message", "hello"));
            assertEquals("hello", result.get("message"));
        }

        @Test
        void requiredIntegerFromInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", 42));
            assertEquals(42, result.get("count"));
        }

        @Test
        void requiredBooleanParsed() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredBoolean("flag", "A flag"));
            Map<String, Object> result = p.parse(Map.of("flag", true));
            assertEquals(true, result.get("flag"));
        }

        @Test
        void requiredNumberParsed() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredNumber("value", "A value"));
            Map<String, Object> result = p.parse(Map.of("value", 3.14));
            assertEquals(3.14, (Double) result.get("value"), 0.001);
        }

        @Test
        void allParameterTypesTogether() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("s", "str")
                    .requiredInteger("i", "int")
                    .requiredNumber("n", "num")
                    .requiredBoolean("b", "bool"));
            Map<String, Object> result = p.parse(Map.of(
                    "s", "text", "i", 5, "n", 2.7, "b", false));
            assertEquals("text", result.get("s"));
            assertEquals(5, result.get("i"));
            assertEquals(2.7, (Double) result.get("n"), 0.001);
            assertEquals(false, result.get("b"));
        }

        @Test
        void optionalParamPresentIsIncluded() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name")
                    .optionalString("title", "Title"));
            Map<String, Object> result = p.parse(Map.of("name", "Alice", "title", "Dr."));
            assertEquals("Alice", result.get("name"));
            assertEquals("Dr.", result.get("title"));
        }

        @Test
        void optionalParamAbsentIsOmitted() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name")
                    .optionalString("title", "Title"));
            Map<String, Object> result = p.parse(Map.of("name", "Alice"));
            assertEquals("Alice", result.get("name"));
            assertFalse(result.containsKey("title"));
        }

        @Test
        void optionalParamNullIsOmitted() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name")
                    .optionalString("title", "Title"));
            Map<String, Object> result = p.parse(mapWithNulls("name", "Alice", "title", null));
            assertTrue(result.containsKey("name"));
            assertFalse(result.containsKey("title"));
        }

        @Test
        void arrayParamPassedThrough() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredArray("items", "Items"));
            var items = java.util.List.of("a", "b", "c");
            Map<String, Object> result = p.parse(Map.of("items", items));
            assertEquals(items, result.get("items"));
        }

        @Test
        void objectParamPassedThrough() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredObject("config", "Config"));
            var config = Map.of("key", "value");
            Map<String, Object> result = p.parse(Map.of("config", config));
            assertEquals(config, result.get("config"));
        }
    }

    // ===== integer coercion =====

    @Nested
    class IntegerCoercion {

        @Test
        void longCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", 42L));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(42, result.get("count"));
        }

        @Test
        void longZeroCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", 0L));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(0, result.get("count"));
        }

        @Test
        void longNegativeCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", -100L));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(-100, result.get("count"));
        }

        @Test
        void longMaxIntValueCoerced() {
            Map<String, Object> result = intTool().parse(Map.of("count", (long) Integer.MAX_VALUE));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(Integer.MAX_VALUE, result.get("count"));
        }

        @Test
        void longMinIntValueCoerced() {
            Map<String, Object> result = intTool().parse(Map.of("count", (long) Integer.MIN_VALUE));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(Integer.MIN_VALUE, result.get("count"));
        }

        @Test
        void wholeDoubleCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", 5.0));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(5, result.get("count"));
        }

        @Test
        void negativeWholeDoubleCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", -3.0));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(-3, result.get("count"));
        }

        @Test
        void zeroDoubleCoercedToInt() {
            Map<String, Object> result = intTool().parse(Map.of("count", 0.0));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(0, result.get("count"));
        }

        @Test
        void longOverflowThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", (long) Integer.MAX_VALUE + 1)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("out of 32-bit integer range"));
            assertTrue(ex.getMessage().contains("'count'"));
        }

        @Test
        void longUnderflowThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", (long) Integer.MIN_VALUE - 1)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("out of 32-bit integer range"));
        }

        @Test
        void fractionalDoubleThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", 5.5)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("must be a whole number"));
            assertTrue(ex.getMessage().contains("5.5"));
        }

        @Test
        void nanDoubleThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", Double.NaN)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("must be a whole number"));
        }

        @Test
        void infinityDoubleThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", Double.POSITIVE_INFINITY)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("must be a whole number"));
        }

        @Test
        void negativeInfinityDoubleThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", Double.NEGATIVE_INFINITY)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("must be a whole number"));
        }

        @Test
        void doubleOverflowIntRangeThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> intTool().parse(Map.of("count", 3_000_000_000.0)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("out of 32-bit integer range"));
        }

        @Test
        void integerPassedThroughUnchanged() {
            Map<String, Object> result = intTool().parse(Map.of("count", 99));
            assertInstanceOf(Integer.class, result.get("count"));
            assertEquals(99, result.get("count"));
        }

        @Test
        void stringPassedThroughForIntegerParam() {
            // String-encoded numbers are coerced later, by Parameters. See D_coerce_string_numbers.
            Map<String, Object> result = intTool().parse(Map.of("count", "42"));
            assertEquals("42", result.get("count"));
        }
    }

    // ===== missing required parameters =====

    @Nested
    class MissingRequired {

        @Test
        void missingRequiredThrows() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> stringTool().parse(Map.of()));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertEquals("Missing required parameter 'message'", ex.getMessage());
        }

        @Test
        void nullRequiredTreatedAsMissing() {
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> stringTool().parse(mapWithNulls("message", null)));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertEquals("Missing required parameter 'message'", ex.getMessage());
        }

        @Test
        void missingOneOfMultipleRequired() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("first", "First")
                    .requiredString("second", "Second"));
            MCPServerException ex = assertThrows(MCPServerException.class,
                    () -> p.parse(Map.of("first", "hello")));
            assertEquals(MCPServerException.INVALID_PARAMS, ex.getCode());
            assertTrue(ex.getMessage().contains("'second'"));
        }
    }

    // ===== unknown parameters =====

    @Nested
    class UnknownParams {

        @Test
        void singleUnknownParamThrows() {
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> stringTool().parse(Map.of("message", "hi", "extra", "oops")));
            assertTrue(ex.getMessage().contains("Unknown parameter 'extra'"));
            assertTrue(ex.getMessage().contains("test_tool"));
        }

        @Test
        void unknownParamOnEmptySchemaThrows() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder());
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("surprise", "value")));
            assertTrue(ex.getMessage().contains("Unknown parameter 'surprise'"));
            assertTrue(ex.getMessage().contains("tool"));
        }

        @Test
        void unknownParamListsValidParameters() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredInteger("a", "first")
                    .requiredInteger("b", "second"));
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("x", 1)));
            assertTrue(ex.getMessage().contains("Valid parameters:"));
            assertTrue(ex.getMessage().contains("a"));
            assertTrue(ex.getMessage().contains("b"));
        }

        @Test
        void unknownParamNoValidParamsListWhenSchemaEmpty() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder());
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("x", 1)));
            assertFalse(ex.getMessage().contains("Valid parameters:"));
        }

        @Test
        void multipleUnknownParamsUsePluralHeader() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name"));
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("foo", 1, "bar", 2)));
            assertTrue(ex.getMessage().startsWith("Unknown parameters "));
            assertTrue(ex.getMessage().contains("'foo'"));
            assertTrue(ex.getMessage().contains("'bar'"));
        }

        @Test
        void singleUnknownParamUsesSingularHeader() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name"));
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("foo", 1)));
            assertTrue(ex.getMessage().startsWith("Unknown parameter 'foo'"));
        }
    }

    // ===== did-you-mean hints =====

    @Nested
    class DidYouMean {

        @Test
        void closeTypoSuggestCorrectName() {
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> stringTool().parse(Map.of("mesage", "hi")));
            assertTrue(ex.getMessage().contains("did you mean 'message'?"));
        }

        @Test
        void completelyDifferentNameNoSuggestion() {
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> stringTool().parse(Map.of("xyz", "hi")));
            assertFalse(ex.getMessage().contains("did you mean"));
        }

        @Test
        void singleCharDifference() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("name", "Name"));
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("nme", "hi")));
            assertTrue(ex.getMessage().contains("did you mean 'name'?"));
        }

        @Test
        void swappedCharsSuggested() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .requiredString("count", "Count"));
            MCPErrorResponseException ex = assertThrows(MCPErrorResponseException.class,
                    () -> p.parse(Map.of("cuont", "hi")));
            assertTrue(ex.getMessage().contains("did you mean 'count'?"));
        }
    }

    // ===== edge cases =====

    @Nested
    class EdgeCases {

        @Test
        void nullPropertiesInSchemaTreatedAsEmpty() {
            MCPProtocol.InputSchema schema = new MCPProtocol.InputSchema();
            schema.setProperties(null);
            schema.setRequired(null);
            MCPParameterParser p = new MCPParameterParser("tool", schema);
            Map<String, Object> result = p.parse(Map.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void nullRequiredListInSchemaTreatedAsEmpty() {
            MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                    .optionalString("opt", "Optional")
                    .build();
            schema.setRequired(null);
            MCPParameterParser p = new MCPParameterParser("tool", schema);
            Map<String, Object> result = p.parse(Map.of("opt", "val"));
            assertEquals("val", result.get("opt"));
        }

        @Test
        void allOptionalParamsAbsentReturnsEmptyMap() {
            MCPParameterParser p = parser("tool", new InputSchemaBuilder()
                    .optionalString("a", "A")
                    .optionalInteger("b", "B"));
            Map<String, Object> result = p.parse(Map.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }
}
