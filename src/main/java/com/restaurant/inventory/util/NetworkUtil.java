package com.restaurant.inventory.util;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * NETWORKING &amp; DATA PARSING.
 *
 * Makes a real HTTP GET request over the internet to a public, free, no-API-key JSON
 * endpoint and parses the JSON response (using the org.json library, see pom.xml).
 * Used by the "Kitchen Tools" tab's "Fetch live USD -> BDT rate" button so the specials
 * board can quote imported-ingredient costs in Taka using today's real exchange rate.
 *
 * This is always called from a background thread (see InventoryService.getExecutor())
 * so a slow or unreachable network never freezes the JavaFX UI thread.
 */
public final class NetworkUtil {

    private static final String EXCHANGE_RATE_URL = "https://api.exchangerate-api.com/v4/latest/USD";

    private NetworkUtil() { }

    /**
     * Fetches the current USD -> BDT exchange rate.
     *
     * @return the number of Bangladeshi Taka that 1 US Dollar buys right now.
     * @throws Exception if there is no internet connection, the request times out,
     *                    or the response cannot be parsed - callers should catch this
     *                    (e.g. inside a javafx.concurrent.Task) and show an error alert.
     */
    public static double fetchUsdToBdtRate() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EXCHANGE_RATE_URL))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Exchange rate service returned HTTP " + response.statusCode());
        }

        // ---- DATA PARSING: the JSON body looks like {"base":"USD","rates":{"BDT":119.5,...}} ----
        JSONObject json = new JSONObject(response.body());
        JSONObject rates = json.getJSONObject("rates");
        return rates.getDouble("BDT");
    }
}
