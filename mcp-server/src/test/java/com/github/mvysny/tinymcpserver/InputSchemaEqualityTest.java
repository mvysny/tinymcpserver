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

import com.github.mvysny.tinymcpserver.MCPProtocol.InputSchema;
import com.github.mvysny.tinymcpserver.MCPProtocol.PropertySchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contract of the equality that manifest-coherence tests compare descriptors
 * with. See D_structural_schema_equality.
 */
class InputSchemaEqualityTest {

    @Test
    void emptySchemasAreEqual() {
        assertEquals(new InputSchema(), new InputSchema());
        assertEquals(new InputSchema().hashCode(), new InputSchema().hashCode());
    }

    @Test
    void differentTypesAreNotEqual() {
        InputSchema a = new InputSchema();
        a.setType("object");
        InputSchema b = new InputSchema();
        b.setType("array");
        assertNotEquals(a, b);
    }

    @Test
    void requiredCompareAsSet_ignoresOrder() {
        InputSchema a = new InputSchema();
        a.setRequired(List.of("ref", "value"));
        InputSchema b = new InputSchema();
        b.setRequired(List.of("value", "ref"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void requiredCompareAsSet_detectsDifferentMembers() {
        InputSchema a = new InputSchema();
        a.setRequired(List.of("ref"));
        InputSchema b = new InputSchema();
        b.setRequired(List.of("value"));
        assertNotEquals(a, b);
    }

    @Test
    void requiredCompareAsSet_detectsCardinalityMismatch() {
        InputSchema a = new InputSchema();
        a.setRequired(List.of("ref"));
        InputSchema b = new InputSchema();
        b.setRequired(List.of("ref", "value"));
        assertNotEquals(a, b);
    }

    @Test
    void requiredNullVsEmpty_distinguished() {
        // null and empty are different — both are valid wire shapes.
        InputSchema empty = new InputSchema();
        empty.setRequired(List.of());
        InputSchema noField = new InputSchema();
        assertNotEquals(empty, noField);
    }

    @Test
    void propertyMapEqualsRegardlessOfInsertionOrder() {
        InputSchema a = new InputSchema();
        Map<String, PropertySchema> aProps = new LinkedHashMap<>();
        aProps.put("ref", stringProp("the ref"));
        aProps.put("value", stringProp("the value"));
        a.setProperties(aProps);

        InputSchema b = new InputSchema();
        Map<String, PropertySchema> bProps = new LinkedHashMap<>();
        bProps.put("value", stringProp("the value"));
        bProps.put("ref", stringProp("the ref"));
        b.setProperties(bProps);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void propertyDescriptionDifferenceDetected() {
        InputSchema a = new InputSchema();
        a.setProperties(Map.of("ref", stringProp("the ref")));
        InputSchema b = new InputSchema();
        b.setProperties(Map.of("ref", stringProp("a different description")));
        assertNotEquals(a, b);
    }

    @Test
    void propertyTypeDifferenceDetected() {
        InputSchema a = new InputSchema();
        a.setProperties(Map.of("ref", prop("string", "x")));
        InputSchema b = new InputSchema();
        b.setProperties(Map.of("ref", prop("integer", "x")));
        assertNotEquals(a, b);
    }

    @Test
    void propertyEnumOrderMatters() {
        // Unlike required, enum is compared in order (D_structural_schema_equality).
        PropertySchema a = stringProp("dir");
        a.setEnumValues(List.of("up", "down"));
        PropertySchema b = stringProp("dir");
        b.setEnumValues(List.of("down", "up"));
        assertNotEquals(a, b);
    }

    @Test
    void propertyEnumSameOrderEqual() {
        PropertySchema a = stringProp("dir");
        a.setEnumValues(List.of("up", "down"));
        PropertySchema b = stringProp("dir");
        b.setEnumValues(List.of("up", "down"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void roundTripThroughGsonPreservesEquality() {
        // The exact pair a coherence test compares: a static descriptor against
        // the server's listTools wire response.
        InputSchema original = new InputSchemaBuilder()
                .requiredInteger("ref", "the element ref")
                .optionalString("value", "optional value")
                .build();
        String json = MCPProtocol.toJson(original);
        InputSchema roundTripped = MCPProtocol.fromJson(json, InputSchema.class);
        assertEquals(original, roundTripped);
        assertEquals(original.hashCode(), roundTripped.hashCode());
    }

    @Test
    void inequalityWithNullAndOtherTypes() {
        InputSchema schema = new InputSchema();
        assertNotEquals(schema, null);
        assertNotEquals(schema, "not a schema");
    }

    private static PropertySchema stringProp(String description) {
        return prop("string", description);
    }

    private static PropertySchema prop(String type, String description) {
        PropertySchema p = new PropertySchema();
        p.setType(type);
        p.setDescription(description);
        return p;
    }
}
