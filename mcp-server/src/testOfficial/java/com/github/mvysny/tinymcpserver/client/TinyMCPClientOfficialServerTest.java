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
package com.github.mvysny.tinymcpserver.client;

import com.github.mvysny.tinymcpserver.MCPProtocol;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link TinyMCPClient} against the official SDK's server, embedded in
 * Jetty. {@code HttpMCPServer} cannot make this check: it shares the client's
 * {@code MCPProtocol} POJOs, so both would agree on the same wrong field name.
 */
class TinyMCPClientOfficialServerTest {

    private static Server jetty;
    private static URI serverUrl;

    private static final String ECHO_TOOL_NAME = "echo";
    private static final String FAILING_TOOL_NAME = "failing";

    @BeforeAll
    static void startOfficialMcpServer() throws Exception {
        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
                .builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();

        McpServerFeatures.SyncToolSpecification echo = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(ECHO_TOOL_NAME)
                        .description("Echoes its 'msg' input.")
                        .inputSchema(jsonMapper, """
                                {
                                  "type": "object",
                                  "properties": { "msg": { "type": "string" } },
                                  "required": ["msg"]
                                }
                                """)
                        .build())
                .callHandler((exchange, req) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(
                                "echo: " + req.arguments().get("msg"))))
                        .isError(false)
                        .build())
                .build();

        McpServerFeatures.SyncToolSpecification failing = McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(FAILING_TOOL_NAME)
                        .description("Always returns isError=true.")
                        .inputSchema(jsonMapper, """
                                { "type": "object", "properties": {} }
                                """)
                        .build())
                .callHandler((exchange, req) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("kaboom")))
                        .isError(true)
                        .build())
                .build();

        McpSyncServer ignored = McpServer.sync(transport)
                .serverInfo("official-mcp-test-server", "1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(echo, failing)
                .build();

        jetty = new Server(0); // OS-assigned ephemeral port
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(transport), "/mcp/*");
        jetty.setHandler(context);
        jetty.start();

        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
        serverUrl = URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    @AfterAll
    static void stop() throws Exception {
        if (jetty != null) {
            jetty.stop();
            jetty = null;
        }
    }

    @Test
    void initializeListAndCallEcho() throws IOException {
        try (MCPClient client = new TinyMCPClient(serverUrl)) {
            MCPProtocol.InitializeResult init = client.initialize();
            assertNotNull(init);
            assertEquals("official-mcp-test-server", init.getServerInfo().getName());

            List<MCPProtocol.Tool> tools = client.listTools();
            assertEquals(2, tools.size());
            assertTrue(tools.stream().anyMatch(t -> ECHO_TOOL_NAME.equals(t.getName())),
                    "expected the echo tool to be advertised");

            MCPProtocol.CallToolResult result = client.callTool(ECHO_TOOL_NAME, Map.of("msg", "hi"));
            assertFalse(Boolean.TRUE.equals(result.getIsError()));
            assertEquals(1, result.getContent().size());
            assertEquals("echo: hi", result.getContent().get(0).getText());
        }
    }

    @Test
    void toolErrorSurfacesAsCallToolResultIsError() throws IOException {
        try (MCPClient client = new TinyMCPClient(serverUrl)) {
            client.initialize();
            MCPProtocol.CallToolResult result = client.callTool(FAILING_TOOL_NAME, Map.of());
            // Per D_three_error_layers, isError=true is a normal result, not an exception.
            assertEquals(Boolean.TRUE, result.getIsError());
            assertEquals("kaboom", result.getContent().get(0).getText());
        }
    }

    @Test
    void unknownToolSurfacesAsClientException() throws IOException {
        try (MCPClient client = new TinyMCPClient(serverUrl)) {
            client.initialize();
            // The official server returns a JSON-RPC error for unknown tools.
            MCPClientException ex = assertThrows(MCPClientException.class,
                    () -> client.callTool("does_not_exist", Map.of()));
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    void callsAfterCloseFailFast() throws IOException {
        MCPClient client = new TinyMCPClient(serverUrl);
        client.initialize();
        client.close();
        assertThrows(IllegalStateException.class, client::listTools);
    }
}
