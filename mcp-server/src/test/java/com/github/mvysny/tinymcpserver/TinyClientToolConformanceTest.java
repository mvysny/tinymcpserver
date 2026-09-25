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

import com.github.mvysny.tinymcpserver.client.MCPClient;
import com.github.mvysny.tinymcpserver.client.MCPClientException;
import com.github.mvysny.tinymcpserver.client.TinyMCPClient;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The conformance suite driven by {@link TinyMCPClient} — the leg that also
 * runs on Java 11. See D_conformance_two_clients.
 */
class TinyClientToolConformanceTest extends AbstractToolConformanceTest {

    @Override
    protected MCPClient newClient(String url) {
        return new TinyMCPClient(URI.create(url));
    }

    @Override
    protected RpcError rpcErrorOf(Executable call) {
        final MCPClientException e = assertThrows(MCPClientException.class, call);
        return new RpcError(e.getCode(), e.getMessage());
    }
}
