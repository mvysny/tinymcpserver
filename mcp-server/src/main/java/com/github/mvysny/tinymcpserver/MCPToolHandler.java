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


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The tool registry behind {@link MCPHandler}, and its {@code tools/list} and
 * {@code tools/call} dispatch.
 */
class MCPToolHandler {

    private static final Logger LOG = Logger.getLogger(MCPToolHandler.class.getName());

    private static class RegisteredTool {
        final MCPParameterParser parser;
        final ToolFunction function;
        final MCPProtocol.Tool descriptor;

        RegisteredTool(ToolDescriptor descriptor, ToolFunction function) {
            this.parser = new MCPParameterParser(descriptor.name(), descriptor.inputSchema());
            this.function = function;
            this.descriptor = new MCPProtocol.Tool();
            this.descriptor.setName(descriptor.name());
            this.descriptor.setDescription(descriptor.description());
            this.descriptor.setInputSchema(descriptor.inputSchema());
        }
    }

    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    /**
     * @throws IllegalStateException if a tool with the same name is already registered
     */
    void addTool(ToolDescriptor descriptor, ToolFunction function) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(function, "function");
        if (tools.containsKey(descriptor.name())) {
            throw new IllegalStateException("A tool with name '" + descriptor.name() + "' is already registered");
        }
        tools.put(descriptor.name(), new RegisteredTool(descriptor, function));
    }

    MCPProtocol.ListToolsResult handleToolsList() {
        MCPProtocol.ListToolsResult result = new MCPProtocol.ListToolsResult();
        List<MCPProtocol.Tool> toolList = new ArrayList<>();
        for (RegisteredTool rt : tools.values()) {
            toolList.add(rt.descriptor);
        }
        result.setTools(toolList);
        return result;
    }

    /**
     * Dispatches {@code tools/call}. Any exception but {@link MCPServerException}
     * becomes an {@code isError: true} result (D_three_error_layers); an unknown
     * tool is {@code METHOD_NOT_FOUND}.
     *
     * @param transportHeaders empty over stdio
     */
    MCPProtocol.CallToolResult handleToolsCall(MCPProtocol.JsonRpcRequest request,
            Map<String, String> transportHeaders) {
        MCPProtocol.CallToolParams params = request.getParamsAs(MCPProtocol.CallToolParams.class);
        if (params == null || params.getName() == null) {
            throw new MCPServerException(MCPServerException.METHOD_NOT_FOUND, "Method not found");
        }

        String toolName = params.getName();
        RegisteredTool tool = tools.get(toolName);
        if (tool == null) {
            throw new MCPServerException(MCPServerException.METHOD_NOT_FOUND, "Method not found: " + toolName);
        }

        Map<String, Object> rawArgs = params.getArguments() != null ? params.getArguments() : Collections.emptyMap();

        try {
            Map<String, Object> callArgs = tool.parser.parse(rawArgs);
            ToolRequest req = new ToolRequest(toolName,
                    Collections.unmodifiableMap(callArgs),
                    transportHeaders,
                    request.getMeta());
            MCPProtocol.Content content = tool.function.call(req);
            MCPProtocol.CallToolResult result = new MCPProtocol.CallToolResult();
            if (content == null) {
                result.setContent(Collections.emptyList());
            } else {
                result.setContent(Collections.singletonList(content));
            }
            return result;
        } catch (MCPErrorResponseException e) {
            LOG.fine("Tool '" + toolName + "' returned error response: " + e.getMessage());
            return toolError(e.getMessage());
        } catch (MCPServerException e) {
            throw e;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tool '" + toolName + "' threw an exception", e);
            return toolError(e.toString());
        }
    }

    private static MCPProtocol.CallToolResult toolError(String message) {
        MCPProtocol.CallToolResult result = new MCPProtocol.CallToolResult();
        result.setIsError(true);
        result.setContent(Collections.singletonList(MCPProtocol.Content.text(message)));
        return result;
    }
}
