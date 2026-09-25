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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * An in-memory {@link HttpExchange}: serves a fixed request body and captures
 * the response code, headers and body, so no HTTP server has to start.
 */
class FakeHttpExchange extends HttpExchange {

    private final byte[] requestBody;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int responseCode;
    private IOException ioFailure;

    FakeHttpExchange(String requestBody) {
        this.requestBody = requestBody.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Makes every read and write on this exchange fail with {@code failure},
     * standing in for a client that hung up mid-request.
     *
     * @return {@code this}, for chaining onto the constructor
     */
    FakeHttpExchange failIO(IOException failure) {
        this.ioFailure = failure;
        return this;
    }

    // --- Used by JsonRpcExchange ---

    @Override public InputStream getRequestBody() {
        if (ioFailure != null) {
            return new InputStream() {
                @Override public int read() throws IOException { throw ioFailure; }
            };
        }
        return new ByteArrayInputStream(requestBody);
    }
    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public OutputStream getResponseBody() { return responseBody; }
    @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        if (ioFailure != null) {
            throw ioFailure;
        }
        this.responseCode = rCode;
    }
    @Override public void close() { }

    // --- Assertions ---

    @Override public int getResponseCode() { return responseCode; }
    String getResponseBodyString() { return responseBody.toString(StandardCharsets.UTF_8); }

    // --- Unused stubs ---

    @Override public String getRequestMethod() { return "POST"; }
    @Override public URI getRequestURI() { return URI.create("/mcp"); }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public InetSocketAddress getRemoteAddress() { return null; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) { }
    @Override public void setStreams(InputStream i, OutputStream o) { }
    @Override public HttpPrincipal getPrincipal() { return null; }
}
