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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The official MCP SDK's client wearing this project's {@link MCPClient}
 * interface, so one conformance suite can be run through either.
 *
 * <pre>{@code
 * try (MCPClient client = new OfficialMCPClient("http://127.0.0.1:8080/mcp")) {
 *     client.initialize();
 *     client.callTool("echo_text", Map.of("message", "hi"));
 * }
 * }</pre>
 *
 * <p>Conversion happens only after the SDK has parsed the response, so a reply
 * our server malformed still fails inside the SDK. See D_conformance_two_clients.
 */
final class OfficialMCPClient implements MCPClient {

    private final McpSyncClient delegate;

    OfficialMCPClient(String url) {
        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(url)
                .openConnectionOnStartup(false)
                .build();
        this.delegate = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public MCPProtocol.InitializeResult initialize() {
        final McpSchema.InitializeResult sdk = delegate.initialize();
        final MCPProtocol.InitializeResult result = new MCPProtocol.InitializeResult();
        result.setProtocolVersion(sdk.protocolVersion());
        result.setInstructions(sdk.instructions());
        final MCPProtocol.Implementation info = new MCPProtocol.Implementation();
        info.setName(sdk.serverInfo().name());
        info.setVersion(sdk.serverInfo().version());
        result.setServerInfo(info);
        return result;
    }

    @Override
    public List<MCPProtocol.Tool> listTools() {
        final List<MCPProtocol.Tool> tools = new ArrayList<>();
        for (McpSchema.Tool sdkTool : delegate.listTools().tools()) {
            final MCPProtocol.Tool tool = new MCPProtocol.Tool();
            tool.setName(sdkTool.name());
            tool.setDescription(sdkTool.description());
            tool.setInputSchema(toInputSchema(sdkTool.inputSchema()));
            tools.add(tool);
        }
        return tools;
    }

    @Override
    public MCPProtocol.CallToolResult callTool(ToolRequest request) {
        final McpSchema.CallToolResult sdk = delegate.callTool(
                new McpSchema.CallToolRequest(request.name(), request.arguments().raw()));
        final MCPProtocol.CallToolResult result = new MCPProtocol.CallToolResult();
        result.setIsError(sdk.isError());
        final List<MCPProtocol.Content> content = new ArrayList<>();
        for (McpSchema.Content c : sdk.content()) {
            content.add(toContent(c));
        }
        result.setContent(content);
        return result;
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static MCPProtocol.InputSchema toInputSchema(McpSchema.JsonSchema sdk) {
        final MCPProtocol.InputSchema schema = new MCPProtocol.InputSchema();
        if (sdk == null) {
            return schema;
        }
        schema.setType(sdk.type());
        if (sdk.properties() != null) {
            // Names only: re-deriving PropertySchema from the SDK's untyped maps
            // would assert nothing the server side does not already own.
            final Map<String, MCPProtocol.PropertySchema> properties = new LinkedHashMap<>();
            for (String name : sdk.properties().keySet()) {
                properties.put(name, new MCPProtocol.PropertySchema());
            }
            schema.setProperties(properties);
        }
        if (sdk.required() != null) {
            schema.setRequired(new ArrayList<>(sdk.required()));
        }
        return schema;
    }

    private static MCPProtocol.Content toContent(McpSchema.Content sdk) {
        if (sdk instanceof McpSchema.TextContent) {
            return MCPProtocol.Content.text(((McpSchema.TextContent) sdk).text());
        }
        if (sdk instanceof McpSchema.ImageContent) {
            final McpSchema.ImageContent image = (McpSchema.ImageContent) sdk;
            return MCPProtocol.Content.image(image.data(), image.mimeType());
        }
        if (sdk instanceof McpSchema.AudioContent) {
            final McpSchema.AudioContent audio = (McpSchema.AudioContent) sdk;
            return MCPProtocol.Content.audio(audio.data(), audio.mimeType());
        }
        if (sdk instanceof McpSchema.EmbeddedResource) {
            final McpSchema.ResourceContents embedded = ((McpSchema.EmbeddedResource) sdk).resource();
            final MCPProtocol.ResourceContents contents = new MCPProtocol.ResourceContents();
            contents.setUri(embedded.uri());
            contents.setMimeType(embedded.mimeType());
            if (embedded instanceof McpSchema.TextResourceContents) {
                contents.setText(((McpSchema.TextResourceContents) embedded).text());
            } else if (embedded instanceof McpSchema.BlobResourceContents) {
                contents.setBlob(((McpSchema.BlobResourceContents) embedded).blob());
            }
            return MCPProtocol.Content.resource(contents);
        }
        throw new AssertionError("unmapped SDK content type: " + sdk.getClass().getName());
    }
}
