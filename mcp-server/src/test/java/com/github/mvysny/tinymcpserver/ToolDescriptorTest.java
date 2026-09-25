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

import com.github.mvysny.tinymcpserver.InputSchemaBuilder;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolDescriptorTest {

    @Test
    void recordRejectsNullFields() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder().build();
        assertThrows(NullPointerException.class,
                () -> new ToolDescriptor(null, "desc", schema));
        assertThrows(NullPointerException.class,
                () -> new ToolDescriptor("name", null, schema));
        assertThrows(NullPointerException.class,
                () -> new ToolDescriptor("name", "desc", null));
    }

    @Test
    void recordRejectsBlankName() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("", "desc", schema));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("  ", "desc", schema));
    }

    @Test
    void recordRejectsInvalidName() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("1tool", "desc", schema));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("my-tool", "desc", schema));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("my tool", "desc", schema));
    }

    @Test
    void recordRejectsBlankDescription() {
        MCPProtocol.InputSchema schema = new InputSchemaBuilder().build();
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("my_tool", "", schema));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("my_tool", "  ", schema));
    }

    @Test
    void equalDescriptorsCompareEqual() {
        ToolDescriptor a = new ToolDescriptor("demo_click",
                "Click a UI element by ref",
                new InputSchemaBuilder()
                        .requiredInteger("ref", "the ref")
                        .build());
        ToolDescriptor b = new ToolDescriptor("demo_click",
                "Click a UI element by ref",
                new InputSchemaBuilder()
                        .requiredInteger("ref", "the ref")
                        .build());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentDescriptionDetected() {
        ToolDescriptor a = new ToolDescriptor("t", "old", new InputSchemaBuilder().build());
        ToolDescriptor b = new ToolDescriptor("t", "new", new InputSchemaBuilder().build());
        assertNotEquals(a, b);
    }

    @Test
    void differentSchemaDetected() {
        ToolDescriptor a = new ToolDescriptor("t", "d",
                new InputSchemaBuilder().requiredInteger("ref", "r").build());
        ToolDescriptor b = new ToolDescriptor("t", "d",
                new InputSchemaBuilder().requiredString("ref", "r").build());
        assertNotEquals(a, b);
    }
}
