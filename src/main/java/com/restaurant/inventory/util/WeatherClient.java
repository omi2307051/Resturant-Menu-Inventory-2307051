package com.restaurant.inventory.util;

import com.restaurant.inventory.model.WeatherInfo;
import org.json.JSONObject;

import java.time.LocalDateTime;

/**
 * NETWORKING &amp; DATA PARSING #2 - the Open-Meteo weather API (free, no API key).
 *
 * Asks for the CURRENT weather at Khulna and parses the JSON reply, which looks like:
 * {"current":{"time":"2026-09-28T14:30","temperature_2m":31.2,"apparent_temperature":37.9,
 *             "relative_humidity_2m":74,"precipitation":0.0,"weather_code":2,"wind_speed_10m":11.5}}
 *
 * Always call from a background thread (see InventoryService.fetchWeatherTask()).
 */
public final class WeatherClient {

    public static final String PLACE = "Khulna";
    private static final double LATITUDE = 22.8456;
    private static final double LONGITUDE = 89.5403;

    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=" + LATITUDE + "&longitude=" + LONGITUDE
            + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m"
            + "&timezone=Asia%2FDhaka";

    private WeatherClient() { }

    public static WeatherInfo fetchKhulna() throws Exception {
        return parse(NetworkUtil.httpGet(URL, 10));
    }

    /** DATA PARSING (separate from the HTTP call so it can be tested without internet). */
    static WeatherInfo parse(String json) {
        JSONObject current = new JSONObject(json).getJSONObject("current");
        return new WeatherInfo(
                PLACE,
                current.getDouble("temperature_2m"),
                current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
                (int) Math.round(current.optDouble("relative_humidity_2m", 0)),
                current.optDouble("precipitation", 0),
                current.optDouble("wind_speed_10m", 0),
                current.getInt("weather_code"),
                LocalDateTime.now());
    }
}
