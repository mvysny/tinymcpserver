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

import static org.junit.jupiter.api.Assertions.*;

/** Registration-level invariants; descriptor field validation is {@link com.github.mvysny.tinymcpserver.ToolDescriptorTest}'s. */
class MCPToolHandlerTest {

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(name, "desc", new InputSchemaBuilder().build());
    }

    @Test
    void addToolRejectsNullDescriptor() {
        MCPToolHandler handler = new MCPToolHandler();
        assertThrows(NullPointerException.class, () ->
                handler.addTool(null, request -> null));
    }

    @Test
    void addToolRejectsNullFunction() {
        MCPToolHandler handler = new MCPToolHandler();
        assertThrows(NullPointerException.class, () ->
                handler.addTool(descriptor("my_tool"), null));
    }

    @Test
    void addToolDuplicateNameThrows() {
        MCPToolHandler handler = new MCPToolHandler();
        handler.addTool(descriptor("my_tool"), request -> null);
        assertThrows(IllegalStateException.class, () ->
                handler.addTool(descriptor("my_tool"), request -> null));
    }
}
