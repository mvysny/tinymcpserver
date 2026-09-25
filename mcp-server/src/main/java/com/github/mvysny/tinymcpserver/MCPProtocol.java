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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The MCP wire format: one GSON-mapped POJO per message type, and the GSON
 * instance that maps them.
 *
 * @implNote {@code @NullUnmarked}, against the package default: these are wire
 * DTOs populated reflectively by GSON, and which fields may be absent is the MCP
 * specification's call per message type, not this class's. Claiming non-null
 * accessors here would be a contract the wire does not honour. Methods that do
 * carry a nullness contract of their own state it with {@code @Nullable}.
 */
@NullUnmarked
public class MCPProtocol {

    /** An untyped number — a tool argument, say — parses as a {@code Long} if it can, else a {@code Double}. */
    private static final Gson GSON = new GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    static Gson gson() {
        return GSON;
    }

    public interface IsJson {
        default String toJson() {
            return MCPProtocol.toJson(this);
        }
    }

    /** {@code toString}, {@code equals} and {@code hashCode} all go through the JSON form. */
    public static abstract class McpPojo implements IsJson {
        @Override
        public String toString() {
            return toJson();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return toJson().equals(((IsJson) o).toJson());
        }

        @Override
        public int hashCode() {
            return toJson().hashCode();
        }
    }

    // ===== JSON-RPC 2.0 Envelope =====

    public static class JsonRpcRequest extends McpPojo {
        private String jsonrpc = "2.0";
        private Object id;
        private String method;
        private JsonElement params;

        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public Object getId() { return id; }
        public void setId(Object id) { this.id = id; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public JsonElement getParams() { return params; }
        public void setParams(JsonElement params) { this.params = params; }

        public <T> T getParamsAs(Class<T> clazz) {
            return GSON.fromJson(params, clazz);
        }

        public void setParamsFrom(Object obj) {
            this.params = GSON.toJsonTree(obj);
        }

        /**
         * Returns {@code params._meta}, where MCP carries cross-cutting fields
         * such as {@code progressToken}.
         *
         * @return the {@code _meta} object, or {@code null} if the request has
         * no params, the params are not a JSON object, {@code _meta} is absent,
         * or {@code _meta} is not itself a JSON object
         */
        public @Nullable JsonObject getMeta() {
            if (params == null || !params.isJsonObject()) {
                return null;
            }
            JsonElement metaElement = params.getAsJsonObject().get("_meta");
            if (metaElement == null || !metaElement.isJsonObject()) {
                return null;
            }
            return metaElement.getAsJsonObject();
        }
    }

    public static class JsonRpcResponse extends McpPojo {
        private String jsonrpc = "2.0";
        private Object id;
        private JsonElement result;

        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public Object getId() { return id; }
        public void setId(Object id) { this.id = id; }
        public JsonElement getResult() { return result; }
        public void setResult(JsonElement result) { this.result = result; }

        public <T> T getResultAs(Class<T> clazz) {
            return GSON.fromJson(result, clazz);
        }

        public void setResultFrom(Object obj) {
            this.result = GSON.toJsonTree(obj);
        }
    }

    public static class JsonRpcError extends McpPojo {
        private String jsonrpc = "2.0";
        private Object id;
        private ErrorObject error;

        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public Object getId() { return id; }
        public void setId(Object id) { this.id = id; }
        public ErrorObject getError() { return error; }
        public void setError(ErrorObject error) { this.error = error; }
    }

    public static class JsonRpcNotification extends McpPojo {
        private String jsonrpc = "2.0";
        private String method;
        private JsonElement params;

        public String getJsonrpc() { return jsonrpc; }
        public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public JsonElement getParams() { return params; }
        public void setParams(JsonElement params) { this.params = params; }

        public <T> T getParamsAs(Class<T> clazz) {
            return GSON.fromJson(params, clazz);
        }

        public void setParamsFrom(Object obj) {
            this.params = GSON.toJsonTree(obj);
        }
    }

    public static class ErrorObject extends McpPojo {
        private int code;
        private String message;
        private JsonElement data;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public JsonElement getData() { return data; }
        public void setData(JsonElement data) { this.data = data; }
    }

    // ===== Common Types =====

    public static class Implementation extends McpPojo {
        private String name;
        private String version;

        public Implementation() {}

        public Implementation(String name, String version) {
            this.name = name;
            this.version = version;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    /**
     * Unified content type covering TextContent, ImageContent, AudioContent,
     * and EmbeddedResource. The {@code type} field discriminates:
     * "text", "image", "audio", or "resource".
     */
    public static class Content extends McpPojo {
        private String type;
        private String text;
        private String data;
        private String mimeType;
        private ResourceContents resource;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public ResourceContents getResource() { return resource; }
        public void setResource(ResourceContents resource) { this.resource = resource; }

        public static Content text(String text) {
            Content c = new Content();
            c.setType("text");
            c.setText(text);
            return c;
        }

        /**
         * Returns a text item holding {@code value} serialized as JSON — a
         * {@code List} or {@code Map}, typically.
         */
        public static Content json(Object value) {
            return text(MCPProtocol.toJson(value));
        }

        /**
         * @param data     base64-encoded
         * @param mimeType e.g. {@code "image/png"}
         */
        public static Content image(String data, String mimeType) {
            Content c = new Content();
            c.setType("image");
            c.setData(data);
            c.setMimeType(mimeType);
            return c;
        }

        /** Returns an image item holding {@code image} as a base64 PNG. */
        public static Content image(java.awt.image.BufferedImage image) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", baos);
                String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
                return image(base64, "image/png");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to encode BufferedImage as PNG", e);
            }
        }

        /**
         * @param data     base64-encoded
         * @param mimeType e.g. {@code "audio/wav"}
         */
        public static Content audio(String data, String mimeType) {
            Content c = new Content();
            c.setType("audio");
            c.setData(data);
            c.setMimeType(mimeType);
            return c;
        }

        public static Content resource(ResourceContents resource) {
            Content c = new Content();
            c.setType("resource");
            c.setResource(resource);
            return c;
        }
    }

    public static class Root extends McpPojo {
        private String uri;
        private String name;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    // ===== Capabilities =====

    public static class ClientCapabilities extends McpPojo {
        private RootsCapability roots;
        private SamplingCapability sampling;

        public RootsCapability getRoots() { return roots; }
        public void setRoots(RootsCapability roots) { this.roots = roots; }
        public SamplingCapability getSampling() { return sampling; }
        public void setSampling(SamplingCapability sampling) { this.sampling = sampling; }
    }

    public static class ServerCapabilities extends McpPojo {
        private ToolsCapability tools;
        private ResourcesCapability resources;
        private PromptsCapability prompts;
        private LoggingCapability logging;

        public ToolsCapability getTools() { return tools; }
        public void setTools(ToolsCapability tools) { this.tools = tools; }
        public ResourcesCapability getResources() { return resources; }
        public void setResources(ResourcesCapability resources) { this.resources = resources; }
        public PromptsCapability getPrompts() { return prompts; }
        public void setPrompts(PromptsCapability prompts) { this.prompts = prompts; }
        public LoggingCapability getLogging() { return logging; }
        public void setLogging(LoggingCapability logging) { this.logging = logging; }
    }

    public static class RootsCapability extends McpPojo {
        private Boolean listChanged;

        public Boolean getListChanged() { return listChanged; }
        public void setListChanged(Boolean listChanged) { this.listChanged = listChanged; }
    }

    public static class SamplingCapability extends McpPojo {
    }

    public static class ToolsCapability extends McpPojo {
        private Boolean listChanged;

        public Boolean getListChanged() { return listChanged; }
        public void setListChanged(Boolean listChanged) { this.listChanged = listChanged; }
    }

    public static class ResourcesCapability extends McpPojo {
        private Boolean subscribe;
        private Boolean listChanged;

        public Boolean getSubscribe() { return subscribe; }
        public void setSubscribe(Boolean subscribe) { this.subscribe = subscribe; }
        public Boolean getListChanged() { return listChanged; }
        public void setListChanged(Boolean listChanged) { this.listChanged = listChanged; }
    }

    public static class PromptsCapability extends McpPojo {
        private Boolean listChanged;

        public Boolean getListChanged() { return listChanged; }
        public void setListChanged(Boolean listChanged) { this.listChanged = listChanged; }
    }

    public static class LoggingCapability extends McpPojo {
    }

    // ===== Initialize =====

    public static class InitializeParams extends McpPojo {
        private String protocolVersion;
        private ClientCapabilities capabilities;
        private Implementation clientInfo;

        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public ClientCapabilities getCapabilities() { return capabilities; }
        public void setCapabilities(ClientCapabilities capabilities) { this.capabilities = capabilities; }
        public Implementation getClientInfo() { return clientInfo; }
        public void setClientInfo(Implementation clientInfo) { this.clientInfo = clientInfo; }
    }

    public static class InitializeResult extends McpPojo {
        private String protocolVersion;
        private ServerCapabilities capabilities;
        private Implementation serverInfo;
        private String instructions;

        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public ServerCapabilities getCapabilities() { return capabilities; }
        public void setCapabilities(ServerCapabilities capabilities) { this.capabilities = capabilities; }
        public Implementation getServerInfo() { return serverInfo; }
        public void setServerInfo(Implementation serverInfo) { this.serverInfo = serverInfo; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String instructions) { this.instructions = instructions; }
    }

    // ===== Tools =====

    public static class Tool extends McpPojo {
        private String name;
        private String description;
        private InputSchema inputSchema;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public InputSchema getInputSchema() { return inputSchema; }
        public void setInputSchema(InputSchema inputSchema) { this.inputSchema = inputSchema; }
    }

    public static class InputSchema extends McpPojo {
        private String type = "object";
        private Map<String, PropertySchema> properties;
        private List<String> required;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Map<String, PropertySchema> getProperties() { return properties; }
        public void setProperties(Map<String, PropertySchema> properties) { this.properties = properties; }
        public List<String> getRequired() { return required; }
        public void setRequired(List<String> required) { this.required = required; }

        // D_structural_schema_equality: order-insensitive — Map equality
        // already ignores property order, and `required` compares as a set.
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InputSchema other = (InputSchema) o;
            return Objects.equals(type, other.type)
                && Objects.equals(properties, other.properties)
                && Objects.equals(asSet(required), asSet(other.required));
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, properties, asSet(required));
        }

        private static Set<String> asSet(List<String> list) {
            return list == null ? null : new HashSet<>(list);
        }
    }

    public static class PropertySchema extends McpPojo {
        private String type;
        private String description;
        @SerializedName("enum")
        private List<String> enumValues;
        private Number minimum;
        private Number maximum;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getEnumValues() { return enumValues; }
        public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues; }
        public Number getMinimum() { return minimum; }
        public void setMinimum(Number minimum) { this.minimum = minimum; }
        public Number getMaximum() { return maximum; }
        public void setMaximum(Number maximum) { this.maximum = maximum; }

        // D_structural_schema_equality. `enum` stays ordered; the bounds
        // compare by value, because a GSON round-trip changes their boxed
        // Number type.
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PropertySchema other = (PropertySchema) o;
            return Objects.equals(type, other.type)
                && Objects.equals(description, other.description)
                && Objects.equals(enumValues, other.enumValues)
                && numberEquals(minimum, other.minimum)
                && numberEquals(maximum, other.maximum);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, description, enumValues,
                    numberHash(minimum), numberHash(maximum));
        }

        private static boolean numberEquals(Number a, Number b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            // Lossy past 2^53, far beyond any bound a tool schema declares.
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }

        private static int numberHash(Number n) {
            return n == null ? 0 : Double.hashCode(n.doubleValue());
        }
    }

    public static class ListToolsParams extends McpPojo {
        private String cursor;

        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }

    public static class ListToolsResult extends McpPojo {
        private List<Tool> tools;
        private String nextCursor;

        public List<Tool> getTools() { return tools; }
        public void setTools(List<Tool> tools) { this.tools = tools; }
        public String getNextCursor() { return nextCursor; }
        public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    }

    public static class CallToolParams extends McpPojo {
        private String name;
        private Map<String, Object> arguments;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Map<String, Object> getArguments() { return arguments; }
        public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
    }

    public static class CallToolResult extends McpPojo {
        private List<Content> content;
        private Boolean isError;

        public List<Content> getContent() { return content; }
        public void setContent(List<Content> content) { this.content = content; }
        public Boolean getIsError() { return isError; }
        public void setIsError(Boolean isError) { this.isError = isError; }
    }

    // ===== Resources =====

    public static class Resource extends McpPojo {
        private String uri;
        private String name;
        private String description;
        private String mimeType;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    }

    public static class ResourceContents extends McpPojo {
        private String uri;
        private String mimeType;
        private String text;
        private String blob;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getBlob() { return blob; }
        public void setBlob(String blob) { this.blob = blob; }

        public static ResourceContents text(String uri, String mimeType, String text) {
            ResourceContents c = new ResourceContents();
            c.setUri(uri);
            c.setMimeType(mimeType);
            c.setText(text);
            return c;
        }

        /** @param blob base64-encoded */
        public static ResourceContents blob(String uri, String mimeType, String blob) {
            ResourceContents c = new ResourceContents();
            c.setUri(uri);
            c.setMimeType(mimeType);
            c.setBlob(blob);
            return c;
        }
    }

    public static class ResourceTemplate extends McpPojo {
        private String uriTemplate;
        private String name;
        private String description;
        private String mimeType;

        public String getUriTemplate() { return uriTemplate; }
        public void setUriTemplate(String uriTemplate) { this.uriTemplate = uriTemplate; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    }

    public static class ListResourcesParams extends McpPojo {
        private String cursor;

        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }

    public static class ListResourcesResult extends McpPojo {
        private List<Resource> resources;
        private String nextCursor;

        public List<Resource> getResources() { return resources; }
        public void setResources(List<Resource> resources) { this.resources = resources; }
        public String getNextCursor() { return nextCursor; }
        public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    }

    public static class ReadResourceParams extends McpPojo {
        private String uri;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }

    public static class ReadResourceResult extends McpPojo {
        private List<ResourceContents> contents;

        public List<ResourceContents> getContents() { return contents; }
        public void setContents(List<ResourceContents> contents) { this.contents = contents; }
    }

    public static class ListResourceTemplatesParams extends McpPojo {
        private String cursor;

        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }

    public static class ListResourceTemplatesResult extends McpPojo {
        private List<ResourceTemplate> resourceTemplates;
        private String nextCursor;

        public List<ResourceTemplate> getResourceTemplates() { return resourceTemplates; }
        public void setResourceTemplates(List<ResourceTemplate> resourceTemplates) { this.resourceTemplates = resourceTemplates; }
        public String getNextCursor() { return nextCursor; }
        public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    }

    public static class SubscribeParams extends McpPojo {
        private String uri;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }

    public static class UnsubscribeParams extends McpPojo {
        private String uri;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }

    // ===== Prompts =====

    public static class Prompt extends McpPojo {
        private String name;
        private String description;
        private List<PromptArgument> arguments;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<PromptArgument> getArguments() { return arguments; }
        public void setArguments(List<PromptArgument> arguments) { this.arguments = arguments; }
    }

    public static class PromptArgument extends McpPojo {
        private String name;
        private String description;
        private Boolean required;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getRequired() { return required; }
        public void setRequired(Boolean required) { this.required = required; }
    }

    public static class PromptMessage extends McpPojo {
        private String role;
        private Content content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public Content getContent() { return content; }
        public void setContent(Content content) { this.content = content; }
    }

    public static class ListPromptsParams extends McpPojo {
        private String cursor;

        public String getCursor() { return cursor; }
        public void setCursor(String cursor) { this.cursor = cursor; }
    }

    public static class ListPromptsResult extends McpPojo {
        private List<Prompt> prompts;
        private String nextCursor;

        public List<Prompt> getPrompts() { return prompts; }
        public void setPrompts(List<Prompt> prompts) { this.prompts = prompts; }
        public String getNextCursor() { return nextCursor; }
        public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    }

    public static class GetPromptParams extends McpPojo {
        private String name;
        private Map<String, String> arguments;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Map<String, String> getArguments() { return arguments; }
        public void setArguments(Map<String, String> arguments) { this.arguments = arguments; }
    }

    public static class GetPromptResult extends McpPojo {
        private String description;
        private List<PromptMessage> messages;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<PromptMessage> getMessages() { return messages; }
        public void setMessages(List<PromptMessage> messages) { this.messages = messages; }
    }

    // ===== Completion =====

    public static class CompleteParams extends McpPojo {
        private CompletionRef ref;
        private CompletionArgument argument;

        public CompletionRef getRef() { return ref; }
        public void setRef(CompletionRef ref) { this.ref = ref; }
        public CompletionArgument getArgument() { return argument; }
        public void setArgument(CompletionArgument argument) { this.argument = argument; }
    }

    public static class CompletionRef extends McpPojo {
        private String type;
        private String name;
        private String uri;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }

    public static class CompletionArgument extends McpPojo {
        private String name;
        private String value;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class CompleteResult extends McpPojo {
        private Completion completion;

        public Completion getCompletion() { return completion; }
        public void setCompletion(Completion completion) { this.completion = completion; }
    }

    public static class Completion extends McpPojo {
        private List<String> values;
        private Integer total;
        private Boolean hasMore;

        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        public Integer getTotal() { return total; }
        public void setTotal(Integer total) { this.total = total; }
        public Boolean getHasMore() { return hasMore; }
        public void setHasMore(Boolean hasMore) { this.hasMore = hasMore; }
    }

    // ===== Logging =====

    public static class SetLevelParams extends McpPojo {
        private String level;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
    }

    public static class LoggingMessageParams extends McpPojo {
        private String level;
        private String logger;
        private JsonElement data;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getLogger() { return logger; }
        public void setLogger(String logger) { this.logger = logger; }
        public JsonElement getData() { return data; }
        public void setData(JsonElement data) { this.data = data; }
    }

    // ===== Sampling (server → client) =====

    public static class CreateMessageParams extends McpPojo {
        private List<SamplingMessage> messages;
        private ModelPreferences modelPreferences;
        private String systemPrompt;
        private String includeContext;
        private Double temperature;
        private Integer maxTokens;
        private List<String> stopSequences;
        private JsonElement metadata;

        public List<SamplingMessage> getMessages() { return messages; }
        public void setMessages(List<SamplingMessage> messages) { this.messages = messages; }
        public ModelPreferences getModelPreferences() { return modelPreferences; }
        public void setModelPreferences(ModelPreferences modelPreferences) { this.modelPreferences = modelPreferences; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public String getIncludeContext() { return includeContext; }
        public void setIncludeContext(String includeContext) { this.includeContext = includeContext; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public List<String> getStopSequences() { return stopSequences; }
        public void setStopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; }
        public JsonElement getMetadata() { return metadata; }
        public void setMetadata(JsonElement metadata) { this.metadata = metadata; }
    }

    public static class CreateMessageResult extends McpPojo {
        private String role;
        private Content content;
        private String model;
        private String stopReason;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public Content getContent() { return content; }
        public void setContent(Content content) { this.content = content; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getStopReason() { return stopReason; }
        public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    }

    public static class SamplingMessage extends McpPojo {
        private String role;
        private Content content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public Content getContent() { return content; }
        public void setContent(Content content) { this.content = content; }
    }

    public static class ModelPreferences extends McpPojo {
        private List<ModelHint> hints;
        private Double costPriority;
        private Double speedPriority;
        private Double intelligencePriority;

        public List<ModelHint> getHints() { return hints; }
        public void setHints(List<ModelHint> hints) { this.hints = hints; }
        public Double getCostPriority() { return costPriority; }
        public void setCostPriority(Double costPriority) { this.costPriority = costPriority; }
        public Double getSpeedPriority() { return speedPriority; }
        public void setSpeedPriority(Double speedPriority) { this.speedPriority = speedPriority; }
        public Double getIntelligencePriority() { return intelligencePriority; }
        public void setIntelligencePriority(Double intelligencePriority) { this.intelligencePriority = intelligencePriority; }
    }

    public static class ModelHint extends McpPojo {
        private String name;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    // ===== Roots (server → client) =====

    public static class ListRootsResult extends McpPojo {
        private List<Root> roots;

        public List<Root> getRoots() { return roots; }
        public void setRoots(List<Root> roots) { this.roots = roots; }
    }

    // ===== Notification Params =====

    public static class CancelledParams extends McpPojo {
        private Object requestId;
        private String reason;

        public Object getRequestId() { return requestId; }
        public void setRequestId(Object requestId) { this.requestId = requestId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ProgressParams extends McpPojo {
        private Object progressToken;
        private double progress;
        private Double total;

        public Object getProgressToken() { return progressToken; }
        public void setProgressToken(Object progressToken) { this.progressToken = progressToken; }
        public double getProgress() { return progress; }
        public void setProgress(double progress) { this.progress = progress; }
        public Double getTotal() { return total; }
        public void setTotal(Double total) { this.total = total; }
    }

    public static class ResourceUpdatedParams extends McpPojo {
        private String uri;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
    }
}
