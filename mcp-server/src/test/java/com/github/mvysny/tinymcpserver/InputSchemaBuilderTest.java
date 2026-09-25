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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InputSchemaBuilderTest {

    @Test
    void requiredIntegerToString() {
        assertEquals("ref: integer",
                new InputSchemaBuilder()
                        .requiredInteger("ref", "The element reference number")
                        .toString());
    }

    @Test
    void allTypesToString() {
        assertEquals("a: integer, b: string, c: number, d: boolean",
                new InputSchemaBuilder()
                        .requiredInteger("a", "int param")
                        .requiredString("b", "string param")
                        .requiredNumber("c", "number param")
                        .requiredBoolean("d", "boolean param")
                        .toString());
    }

    @Test
    void optionalTypesToString() {
        assertEquals("x: string?, y: number?, z: boolean?",
                new InputSchemaBuilder()
                        .optionalString("x", "optional string")
                        .optionalNumber("y", "optional number")
                        .optionalBoolean("z", "optional boolean")
                        .toString());
    }

    @Test
    void fluentChainingPreservesInsertionOrder() {
        assertEquals("a: integer, b: integer, ref: string",
                new InputSchemaBuilder()
                        .requiredInteger("a", "first")
                        .requiredInteger("b", "second")
                        .requiredString("ref", "third")
                        .toString());
    }

    @Test
    void buildPreservesInsertionOrderInProperties() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .requiredInteger("a", "first")
                .requiredInteger("b", "second")
                .requiredString("ref", "third")
                .build();

        assertEquals(List.of("a", "b", "ref"), List.copyOf(schema.getProperties().keySet()));
    }

    @Test
    void buildProducesCorrectType() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .requiredInteger("ref", "The element reference number")
                .build();
        assertEquals("object", schema.getType());
    }

    @Test
    void buildProducesCorrectProperties() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .requiredInteger("ref", "The element reference number")
                .requiredString("name", "The element name")
                .build();

        Map<String, MCPProtocol.PropertySchema> props = schema.getProperties();
        assertNotNull(props);
        assertEquals(2, props.size());

        assertEquals("integer", props.get("ref").getType());
        assertEquals("The element reference number", props.get("ref").getDescription());

        assertEquals("string", props.get("name").getType());
        assertEquals("The element name", props.get("name").getDescription());
    }

    @Test
    void buildProducesCorrectRequiredList() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .requiredInteger("a", "required int")
                .optionalString("b", "optional string")
                .requiredBoolean("c", "required bool")
                .build();

        List<String> required = schema.getRequired();
        assertNotNull(required);
        assertEquals(List.of("a", "c"), required);
    }

    @Test
    void buildWithNoRequiredFieldsProducesEmptyRequiredList() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .optionalString("x", "opt")
                .optionalInteger("y", "opt2")
                .build();

        assertNotNull(schema.getRequired());
        assertTrue(schema.getRequired().isEmpty());
    }

    @Test
    void emptyBuilderProducesEmptyPropertiesAndRequiredList() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder().build();

        assertNotNull(schema.getProperties());
        assertTrue(schema.getProperties().isEmpty());
        assertNotNull(schema.getRequired());
        assertTrue(schema.getRequired().isEmpty());
    }

    @Test
    void buildWithAllOptionalTypesHasCorrectPropertyTypes() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .optionalString("s", "s")
                .optionalInteger("i", "i")
                .optionalNumber("n", "n")
                .optionalBoolean("b", "b")
                .build();

        Map<String, MCPProtocol.PropertySchema> props = schema.getProperties();
        assertEquals("string", props.get("s").getType());
        assertEquals("integer", props.get("i").getType());
        assertEquals("number", props.get("n").getType());
        assertEquals("boolean", props.get("b").getType());
    }

    @Test
    void toStringShowsRequiredVsOptional() {
        assertEquals("a: integer, b: string?",
                new InputSchemaBuilder()
                        .requiredInteger("a", "required int")
                        .optionalString("b", "optional string")
                        .toString());
    }

    @Test
    void toStringOmitsDescription() {
        String result = new InputSchemaBuilder()
                .requiredString("name", "A very long description that should not appear")
                .toString();
        assertFalse(result.contains("description"));
        assertFalse(result.contains("long"));
        assertEquals("name: string", result);
    }

    @Test
    void emptyBuilderToStringIsEmpty() {
        assertEquals("", new InputSchemaBuilder().toString());
    }

    @Test
    void duplicateParameterThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder()
                        .requiredInteger("ref", "first")
                        .requiredString("ref", "duplicate"));
    }

    @Test
    void nullNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString(null, "desc"));
    }

    @Test
    void blankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("  ", "desc"));
    }

    @Test
    void nullDescriptionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("name", null));
    }

    @Test
    void blankDescriptionThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("name", ""));
    }

    // --- withEnum / withMinimum / withMaximum ---

    @Test
    void withEnumToString() {
        assertEquals("status: string(active|inactive|pending)",
                new InputSchemaBuilder()
                        .requiredString("status", "Current status")
                        .withEnum("active", "inactive", "pending")
                        .toString());
    }

    @Test
    void withMinimumOnlyToString() {
        assertEquals("page: integer[1,]",
                new InputSchemaBuilder()
                        .requiredInteger("page", "Page number")
                        .withMinimum(1)
                        .toString());
    }

    @Test
    void withMaximumOnlyToString() {
        assertEquals("count: integer[,100]",
                new InputSchemaBuilder()
                        .requiredInteger("count", "Max count")
                        .withMaximum(100)
                        .toString());
    }

    @Test
    void withMinimumAndMaximumToString() {
        assertEquals("price: number[0.0,999.99]",
                new InputSchemaBuilder()
                        .requiredNumber("price", "Price in USD")
                        .withMinimum(0.0).withMaximum(999.99)
                        .toString());
    }

    @Test
    void withEnumOptionalToString() {
        assertEquals("status: string?(active|inactive)",
                new InputSchemaBuilder()
                        .optionalString("status", "Status")
                        .withEnum("active", "inactive")
                        .toString());
    }

    @Test
    void withConstraintsApplyToLastAddedOnly() {
        assertEquals("a: integer[1,10], b: string",
                new InputSchemaBuilder()
                        .requiredInteger("a", "first")
                        .withMinimum(1).withMaximum(10)
                        .requiredString("b", "second")
                        .toString());
    }

    @Test
    void withEnumSetOnBuildResult() {
        MCPProtocol.PropertySchema prop = new InputSchemaBuilder()
                .requiredString("status", "Status")
                .withEnum("active", "inactive")
                .build()
                .getProperties().get("status");

        assertEquals(List.of("active", "inactive"), prop.getEnumValues());
    }

    @Test
    void withMinimumAndMaximumSetOnBuildResult() {
        MCPProtocol.PropertySchema prop = new InputSchemaBuilder()
                .requiredNumber("price", "Price")
                .withMinimum(0.0).withMaximum(999.99)
                .build()
                .getProperties().get("price");

        assertEquals(0.0, prop.getMinimum().doubleValue());
        assertEquals(999.99, prop.getMaximum().doubleValue());
    }

    @Test
    void withEnumEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder()
                        .requiredString("status", "desc")
                        .withEnum());
    }

    @Test
    void withEnumBeforeAnyParamThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder().withEnum("a", "b"));
    }

    @Test
    void withMinimumBeforeAnyParamThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder().withMinimum(1));
    }

    @Test
    void withMaximumBeforeAnyParamThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder().withMaximum(10));
    }

    @Test
    void withEnumTwiceThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder()
                        .requiredString("s", "desc")
                        .withEnum("a")
                        .withEnum("b"));
    }

    @Test
    void withMinimumTwiceThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder()
                        .requiredInteger("n", "desc")
                        .withMinimum(1)
                        .withMinimum(2));
    }

    @Test
    void withMaximumTwiceThrows() {
        assertThrows(IllegalStateException.class, () ->
                new InputSchemaBuilder()
                        .requiredInteger("n", "desc")
                        .withMaximum(10)
                        .withMaximum(20));
    }

    @Test
    void nameStartingWithDigitThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("1name", "desc"));
    }

    @Test
    void nameWithSpaceThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("my name", "desc"));
    }

    @Test
    void nameWithSpecialCharThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputSchemaBuilder().requiredString("my-name", "desc"));
    }

    @Test
    void validNamesAccepted() {
        assertEquals("file_path: string, maxResults: integer, _tmp: boolean",
                new InputSchemaBuilder()
                        .requiredString("file_path", "a path")
                        .requiredInteger("maxResults", "max results")
                        .requiredBoolean("_tmp", "temp flag")
                        .toString());
    }

    // --- array and object types ---

    @Test
    void requiredArrayToString() {
        assertEquals("tags: array",
                new InputSchemaBuilder()
                        .requiredArray("tags", "A list of tags")
                        .toString());
    }

    @Test
    void optionalArrayToString() {
        assertEquals("tags: array?",
                new InputSchemaBuilder()
                        .optionalArray("tags", "A list of tags")
                        .toString());
    }

    @Test
    void requiredObjectToString() {
        assertEquals("config: object",
                new InputSchemaBuilder()
                        .requiredObject("config", "Configuration map")
                        .toString());
    }

    @Test
    void optionalObjectToString() {
        assertEquals("config: object?",
                new InputSchemaBuilder()
                        .optionalObject("config", "Configuration map")
                        .toString());
    }

    @Test
    void arrayAndObjectBuildProducesCorrectTypes() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder()
                .requiredArray("tags", "A list of tags")
                .optionalObject("config", "Configuration map")
                .build();

        Map<String, MCPProtocol.PropertySchema> props = schema.getProperties();
        assertEquals("array", props.get("tags").getType());
        assertEquals("object", props.get("config").getType());
        assertEquals(List.of("tags"), schema.getRequired());
    }

    @Test
    void allTypesToStringIncludingArrayAndObject() {
        assertEquals("a: integer, b: string, c: number, d: boolean, e: array, f: object",
                new InputSchemaBuilder()
                        .requiredInteger("a", "int")
                        .requiredString("b", "str")
                        .requiredNumber("c", "num")
                        .requiredBoolean("d", "bool")
                        .requiredArray("e", "arr")
                        .requiredObject("f", "obj")
                        .toString());
    }
}
