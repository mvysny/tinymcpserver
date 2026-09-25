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
import com.github.mvysny.tinymcpserver.InputSchemaBuilder;
import com.github.mvysny.tinymcpserver.MCPErrorResponseException;
import com.github.mvysny.tinymcpserver.MCPHandler;
import com.github.mvysny.tinymcpserver.MCPProtocol;
import com.github.mvysny.tinymcpserver.PromptArgumentsBuilder;
import com.github.mvysny.tinymcpserver.StdioMCPServer;
import com.github.mvysny.tinymcpserver.ToolRequest;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A demo MCP server that tells the weather: two tools, a resource and a prompt
 * over a {@link WeatherStation}.
 *
 * <pre>
 * java -jar weather-demo.jar            # HTTP on http://127.0.0.1:18088/mcp
 * java -jar weather-demo.jar 9000       # HTTP on another port
 * java -jar weather-demo.jar --stdio    # stdio, for a client that spawns the process
 * </pre>
 */
public final class WeatherMCP {

    private static final Logger LOG = Logger.getLogger(WeatherMCP.class.getName());

    private WeatherMCP() {}

    /**
     * A handler with the weather tools registered, ready for either transport.
     *
     * <ul>
     *   <li>{@code get_weather(city)} and {@code get_forecast(city, days)} — one line per day</li>
     *   <li>{@code weather://cities} — the known city names, one per line</li>
     *   <li>{@code plan_outing(city, activity?)} — a prompt that asks the model to use the tools</li>
     * </ul>
     */
    public static MCPHandler createHandler(WeatherStation station) {
        MCPHandler handler = new MCPHandler(new MCPProtocol.Implementation("weather-demo", "1.0"),
                "Invented weather for a few cities. Read weather://cities for the names.");
        String[] cities = station.cities().toArray(new String[0]);

        handler.addTool("get_weather", "Today's weather in a city.",
                new InputSchemaBuilder()
                        .requiredString("city", "The city name").withEnum(cities)
                        .build(),
                request -> MCPProtocol.Content.text(station.current(city(station, request)).toString()));

        handler.addTool("get_forecast", "The weather in a city for the next few days, today first.",
                new InputSchemaBuilder()
                        .requiredString("city", "The city name").withEnum(cities)
                        .requiredInteger("days", "How many days, today included")
                        .withMinimum(1).withMaximum(WeatherStation.MAX_FORECAST_DAYS)
                        .build(),
                request -> {
                    int days = request.arguments().getInt("days");
                    if (days < 1 || days > WeatherStation.MAX_FORECAST_DAYS) {
                        throw new MCPErrorResponseException("days must be 1.." + WeatherStation.MAX_FORECAST_DAYS
                                + ", got " + days);
                    }
                    return MCPProtocol.Content.text(lines(station.forecast(city(station, request), days)));
                });

        handler.addResource("weather://cities", "cities", "The cities this server knows", "text/plain",
                request -> Collections.singletonList(MCPProtocol.ResourceContents.text(
                        request.uri(), "text/plain", String.join("\n", station.cities()))));

        handler.addPrompt("plan_outing", "Plan a day out around the forecast",
                new PromptArgumentsBuilder()
                        .required("city", "Where the outing is")
                        .optional("activity", "What you want to do, e.g. cycling"),
                request -> {
                    String activity = request.arguments().getOrDefault("activity", "a day out");
                    MCPProtocol.PromptMessage message = new MCPProtocol.PromptMessage();
                    message.setRole("user");
                    message.setContent(MCPProtocol.Content.text("I am planning " + activity + " in "
                            + request.arguments().get("city")
                            + " this week. Check the forecast and pick the best day."));
                    MCPProtocol.GetPromptResult result = new MCPProtocol.GetPromptResult();
                    result.setMessages(Collections.singletonList(message));
                    return result;
                });
        return handler;
    }

    /** The {@code city} argument; the schema's enum is advisory, so an unknown name is checked here. */
    private static String city(WeatherStation station, ToolRequest request) {
        String city = request.arguments().getString("city");
        if (!station.knows(city)) {
            throw new MCPErrorResponseException("Unknown city '" + city + "'. Known cities: "
                    + String.join(", ", station.cities()));
        }
        return city;
    }

    private static String lines(List<WeatherStation.DayWeather> days) {
        return days.stream().map(Object::toString).collect(Collectors.joining("\n"));
    }

    public static void main(String[] args) throws InterruptedException {
        MCPHandler handler = createHandler(new WeatherStation(LocalDate.now()));
        if (args.length == 1 && args[0].equals("--stdio")) {
            new StdioMCPServer(handler).runStdio(System.in, System.out);
            return;
        }
        int port = args.length == 1 ? Integer.parseInt(args[0]) : HttpMCPServer.DEFAULT_PORT;
        HttpMCPServer server = new HttpMCPServer(port, HttpMCPServer.DEFAULT_CONTEXT_PATH, handler);
        server.start();
        LOG.info("Weather MCP server running at " + server.getUrl() + "; Ctrl+C to stop");
        // The server's threads are daemons, so main() is what keeps the JVM alive.
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stopped.countDown();
        }));
        stopped.await();
    }
}
