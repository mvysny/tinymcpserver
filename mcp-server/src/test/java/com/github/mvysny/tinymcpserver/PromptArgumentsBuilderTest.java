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

import static org.junit.jupiter.api.Assertions.*;

class PromptArgumentsBuilderTest {

    @Test
    void emptyBuilderProducesEmptyList() {
        assertTrue(new PromptArgumentsBuilder().build().isEmpty());
    }

    @Test
    void requiredAndOptionalBuild() {
        List<MCPProtocol.PromptArgument> args = new PromptArgumentsBuilder()
                .required("name", "Who to greet")
                .optional("style", "Greeting style")
                .build();
        assertEquals(2, args.size());

        MCPProtocol.PromptArgument first = args.get(0);
        assertEquals("name", first.getName());
        assertEquals("Who to greet", first.getDescription());
        assertTrue(first.getRequired());

        MCPProtocol.PromptArgument second = args.get(1);
        assertEquals("style", second.getName());
        assertEquals("Greeting style", second.getDescription());
        assertFalse(second.getRequired());
    }

    @Test
    void buildPreservesInsertionOrder() {
        List<MCPProtocol.PromptArgument> args = new PromptArgumentsBuilder()
                .optional("z", "Z")
                .required("a", "A")
                .optional("m", "M")
                .build();
        assertEquals(List.of("z", "a", "m"),
                args.stream().map(MCPProtocol.PromptArgument::getName)
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().required(null, "desc"));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().optional("  ", "desc"));
    }

    @Test
    void rejectsInvalidName() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().required("1abc", "desc"));
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().required("a-b", "desc"));
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().required("a b", "desc"));
    }

    @Test
    void rejectsNullDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().required("name", null));
    }

    @Test
    void rejectsBlankDescription() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptArgumentsBuilder().optional("name", "  "));
    }

    @Test
    void rejectsDuplicateName() {
        PromptArgumentsBuilder b = new PromptArgumentsBuilder().required("x", "first");
        assertThrows(IllegalStateException.class, () -> b.optional("x", "second"));
    }

    @Test
    void buildReturnsIndependentCopies() {
        PromptArgumentsBuilder b = new PromptArgumentsBuilder().required("x", "X");
        List<MCPProtocol.PromptArgument> a = b.build();
        List<MCPProtocol.PromptArgument> c = b.build();
        assertNotSame(a, c);
        assertEquals(1, a.size());
        assertEquals(1, c.size());
    }
}
