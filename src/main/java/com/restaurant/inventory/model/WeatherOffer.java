package com.restaurant.inventory.model;

import java.util.List;

/**
 * MODEL: a weather-based promotion - "it is hot, so these cold foods get X% off".
 *
 * @param mood      the kind of weather that triggered the offer
 * @param promoName short name printed on the bill, e.g. "Cool Down Deal"
 * @param percent   discount percentage applied to every dish in {@code dishNames}
 * @param message   one friendly sentence shown on the Menu tab
 * @param dishNames names of the suggested (and discounted) dishes
 */
public record WeatherOffer(WeatherInfo.Mood mood, String promoName, int percent,
                           String message, List<String> dishNames) { }
