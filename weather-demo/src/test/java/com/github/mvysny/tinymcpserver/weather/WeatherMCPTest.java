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
package com.github.mvysny.tinymcpserver.weather;

import com.github.mvysny.tinymcpserver.HttpMCPServer;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.client.MCPClient;
import com.github.mvysny.tinymcpserver.client.TinyMCPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The demo end to end: a real HTTP server, driven through {@link TinyMCPClient}. */
class WeatherMCPTest {

    private HttpMCPServer server;
    private MCPClient client;

    @BeforeEach
    void start() throws IOException {
        server = new HttpMCPServer(0, "/mcp",
                WeatherMCP.createHandler(new WeatherStation(LocalDate.of(2026, 9, 25))));
        server.start();
        client = new TinyMCPClient(URI.create(server.getUrl()));
        client.initialize();
    }

    @AfterEach
    void stop() throws IOException {
        client.close();
        server.stop();
    }

    @Test
    void listsBothTools() throws IOException {
        String names = client.listTools().stream().map(MCPProtocol.Tool::getName).collect(Collectors.joining(","));
        assertEquals("get_weather,get_forecast", names);
    }

    @Test
    void currentWeather() throws IOException {
        assertEquals("2026-09-25: fog, 9 °C, wind 11 km/h", call("get_weather", Map.of("city", "Helsinki")));
    }

    @Test
    void forecast() throws IOException {
        assertEquals("2026-09-25: showers, 17 °C, wind 0 km/h\n"
                + "2026-09-26: overcast, 22 °C, wind 19 km/h\n"
                + "2026-09-27: partly cloudy, 15 °C, wind 14 km/h",
                call("get_forecast", Map.of("city", "Lisbon", "days", 3)));
    }

    @Test
    void unknownCityNamesTheKnownOnes() throws IOException {
        assertEquals("error: Unknown city 'Atlantis'. Known cities: Helsinki, Brno, Lisbon, Reykjavik, Tokyo",
                call("get_weather", Map.of("city", "Atlantis")));
    }

    @Test
    void forecastLongerThanAWeekIsRefused() throws IOException {
        assertEquals("error: days must be 1..7, got 8", call("get_forecast", Map.of("city", "Brno", "days", 8)));
    }

    /** The text of the one content item, prefixed {@code error: } when the tool failed. */
    private String call(String tool, Map<String, Object> arguments) throws IOException {
        MCPProtocol.CallToolResult result = client.callTool(tool, arguments);
        List<MCPProtocol.Content> content = result.getContent();
        assertEquals(1, content.size());
        String text = content.get(0).getText();
        return Boolean.TRUE.equals(result.getIsError()) ? "error: " + text : text;
    }
}
