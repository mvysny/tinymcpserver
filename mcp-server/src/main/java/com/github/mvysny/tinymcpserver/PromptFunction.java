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

/**
 * Expands a prompt on {@code prompts/get}. Arguments arrive validated — every
 * required one present, no undeclared one — so read them directly.
 */
@FunctionalInterface
public interface PromptFunction {
    /**
     * @param request an absent optional argument has no key in
     *                {@link PromptRequest#arguments()}
     * @return never null; a null is answered with {@code INTERNAL_ERROR}
     * @throws MCPServerException sent as the JSON-RPC error, as-is
     * @throws Exception          anything else becomes {@code INTERNAL_ERROR}
     */
    MCPProtocol.GetPromptResult call(PromptRequest request) throws Exception;
}
