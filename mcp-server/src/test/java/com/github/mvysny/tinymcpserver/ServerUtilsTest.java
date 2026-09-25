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

class ServerUtilsTest {

    @Test
    void levenshteinIdenticalStrings() {
        assertEquals(0, ServerUtils.levenshteinDistance("abc", "abc"));
    }

    @Test
    void levenshteinSingleEdit() {
        assertEquals(1, ServerUtils.levenshteinDistance("mesage", "message"));
    }

    @Test
    void levenshteinCompletelyDifferent() {
        assertEquals(3, ServerUtils.levenshteinDistance("abc", "xyz"));
    }

    @Test
    void levenshteinEmptyStrings() {
        assertEquals(0, ServerUtils.levenshteinDistance("", ""));
        assertEquals(3, ServerUtils.levenshteinDistance("abc", ""));
        assertEquals(3, ServerUtils.levenshteinDistance("", "abc"));
    }
}
