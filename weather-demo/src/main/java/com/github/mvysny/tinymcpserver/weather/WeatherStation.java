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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Invented weather for a handful of cities, with no network behind it. The
 * same city on the same date always gets the same weather, so a demo run is
 * repeatable and a test can assert the exact text.
 *
 * <pre>{@code
 * WeatherStation station = new WeatherStation(LocalDate.of(2026, 9, 25));
 * station.forecast("Helsinki", 3);  // today, tomorrow, the day after
 * }</pre>
 */
public final class WeatherStation {

    /** The typical temperature per city, in °C, which a day's weather varies around. */
    private static final Map<String, Integer> CLIMATE = new LinkedHashMap<>();

    static {
        CLIMATE.put("Helsinki", 8);
        CLIMATE.put("Brno", 12);
        CLIMATE.put("Lisbon", 19);
        CLIMATE.put("Reykjavik", 5);
        CLIMATE.put("Tokyo", 17);
    }

    private static final String[] SKIES = {"sunny", "partly cloudy", "overcast", "rain", "showers", "fog"};

    /** The longest forecast {@link #forecast} produces, in days. */
    public static final int MAX_FORECAST_DAYS = 7;

    private final LocalDate today;

    /** @param today day 0 of every forecast */
    public WeatherStation(LocalDate today) {
        this.today = Objects.requireNonNull(today, "today");
    }

    /** The cities this station knows, in a stable order. */
    public List<String> cities() {
        return new ArrayList<>(CLIMATE.keySet());
    }

    /** @return false for a city not in {@link #cities()}; the match is exact */
    public boolean knows(String city) {
        return CLIMATE.containsKey(city);
    }

    /**
     * Today's weather in {@code city}.
     *
     * @throws IllegalArgumentException if the station does not {@linkplain #knows know} the city
     */
    public DayWeather current(String city) {
        return forecast(city, 1).get(0);
    }

    /**
     * {@code days} days of weather in {@code city}, today first.
     *
     * @param days 1 to {@value #MAX_FORECAST_DAYS}
     * @throws IllegalArgumentException if the city is unknown, or {@code days} is out of range
     */
    public List<DayWeather> forecast(String city, int days) {
        Integer typical = CLIMATE.get(city);
        if (typical == null) {
            throw new IllegalArgumentException("Unknown city: " + city);
        }
        if (days < 1 || days > MAX_FORECAST_DAYS) {
            throw new IllegalArgumentException("days must be 1.." + MAX_FORECAST_DAYS + ", got " + days);
        }
        List<DayWeather> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = today.plusDays(i);
            Random random = new Random(city.hashCode() * 31L + date.toEpochDay());
            result.add(new DayWeather(date.toString(),
                    SKIES[random.nextInt(SKIES.length)],
                    typical + random.nextInt(9) - 4,
                    random.nextInt(30)));
        }
        return result;
    }

    /** One day's weather; serialized field by field, so the field names are what a client reads. */
    public static final class DayWeather {
        /** ISO-8601, e.g. {@code 2026-09-25}. */
        public final String date;
        public final String sky;
        public final int temperatureCelsius;
        public final int windKmh;

        public DayWeather(String date, String sky, int temperatureCelsius, int windKmh) {
            this.date = date;
            this.sky = sky;
            this.temperatureCelsius = temperatureCelsius;
            this.windKmh = windKmh;
        }

        @Override
        public String toString() {
            return date + ": " + sky + ", " + temperatureCelsius + " °C, wind " + windKmh + " km/h";
        }
    }
}
