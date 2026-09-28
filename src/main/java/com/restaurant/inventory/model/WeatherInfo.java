package com.restaurant.inventory.model;

import java.time.LocalDateTime;

/**
 * MODEL: the current weather at one place, as returned by the Open-Meteo API
 * (see util/WeatherClient). Immutable - a new object is created for every refresh.
 *
 * @param weatherCode WMO weather interpretation code (0 = clear sky, 61 = rain, 95 = thunderstorm ...)
 */
public record WeatherInfo(String place, double tempC, double feelsLikeC, int humidity,
                          double precipMm, double windKmh, int weatherCode, LocalDateTime fetchedAt) {

    /** Broad "mood" of the weather - decides which foods are suggested. */
    public enum Mood { RAINY, HOT, COLD, MILD }

    /** Plain-English description of the WMO weather code. */
    public String condition() {
        return switch (weatherCode) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Foggy";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61 -> "Light rain";
            case 63 -> "Rain";
            case 65 -> "Heavy rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75, 77 -> "Snow";
            case 80 -> "Light rain showers";
            case 81 -> "Rain showers";
            case 82 -> "Violent rain showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }

    /** A small emoji for the title line (written as escapes so the source file stays plain ASCII). */
    public String emoji() {
        if (weatherCode == 0 || weatherCode == 1) return "\u2600";                 // sun
        if (weatherCode == 2) return "\u26C5";                                      // sun behind cloud
        if (weatherCode == 3) return "\u2601";                                      // cloud
        if (weatherCode == 45 || weatherCode == 48) return "\uD83C\uDF2B";          // fog
        if (weatherCode >= 95) return "\u26C8";                                     // thunder cloud with rain
        if (isRaining()) return "\uD83C\uDF27";                                     // rain cloud
        return "\uD83C\uDF21";                                                      // thermometer
    }

    /** True for drizzle / rain / showers / thunderstorm codes, or when rain is actually falling now. */
    public boolean isRaining() {
        boolean rainyCode = (weatherCode >= 51 && weatherCode <= 67)
                || (weatherCode >= 80 && weatherCode <= 82)
                || weatherCode >= 95;
        return rainyCode || precipMm >= 0.2;
    }

    public Mood mood() {
        if (isRaining()) return Mood.RAINY;
        if (feelsLikeC >= 35 || tempC >= 32) return Mood.HOT;
        if (tempC <= 20) return Mood.COLD;
        return Mood.MILD;
    }
}
