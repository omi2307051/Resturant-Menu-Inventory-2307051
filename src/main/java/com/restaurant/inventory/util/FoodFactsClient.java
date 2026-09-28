package com.restaurant.inventory.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NETWORKING &amp; DATA PARSING #3 - the Open Food Facts API (free, no API key).
 *
 * Searches the open food database for a product that matches an ingredient name and reads
 * its nutrition facts (per 100 g), allergens and Nutri-Score from the JSON reply.
 *
 * Fair-use rules from Open Food Facts that this class follows:
 *  - search requests are limited to 10 per minute -> calls are spaced at least 7 seconds apart
 *    (the wait happens on the background thread, never on the UI thread);
 *  - every request carries an identifying User-Agent (see NetworkUtil);
 *  - answers are cached, so looking up the same ingredient twice costs no request.
 *
 * Data licence: Open Food Facts contributors, ODbL - the UI shows this credit.
 */
public final class FoodFactsClient {

    /** What we keep from one matched product. A null number means "not listed for this product". */
    public record Nutrition(String productName, String brand, Double kcal, Double fat, Double saturatedFat,
                            Double carbs, Double sugars, Double protein, Double salt,
                            String nutriScore, List<String> allergens) {

        /** Multi-line text for a Label. */
        public String toDisplayText(String ingredientName) {
            StringBuilder sb = new StringBuilder();
            sb.append("Closest match for \"").append(ingredientName).append("\":\n");
            sb.append(productName);
            if (brand != null && !brand.isBlank()) sb.append("  (").append(brand).append(')');
            sb.append("\n\nPer 100 g:\n");
            sb.append("  Energy         ").append(fmt(kcal, " kcal")).append('\n');
            sb.append("  Fat            ").append(fmt(fat, " g")).append("   (saturated ").append(fmt(saturatedFat, " g")).append(")\n");
            sb.append("  Carbohydrates  ").append(fmt(carbs, " g")).append("   (sugars ").append(fmt(sugars, " g")).append(")\n");
            sb.append("  Protein        ").append(fmt(protein, " g")).append('\n');
            sb.append("  Salt           ").append(fmt(salt, " g")).append('\n');
            sb.append("\nNutri-Score: ").append(nutriScore == null ? "not rated" : nutriScore.toUpperCase(Locale.ROOT));
            sb.append("\nAllergens: ").append(allergens.isEmpty() ? "none listed" : String.join(", ", allergens));
            return sb.toString();
        }

        private static String fmt(Double value, String unit) {
            if (value == null) return "n/a";
            return String.format(Locale.US, "%.1f%s", value, unit);
        }
    }

    /** Result of a lookup that found nothing - cached too, so we do not keep asking. */
    private static final Nutrition NOT_FOUND = new Nutrition(null, null, null, null, null, null, null, null, null, null, List.of());

    private static final String SEARCH_URL = "https://world.openfoodfacts.org/cgi/search.pl"
            + "?search_simple=1&action=process&json=1&page_size=8"
            + "&fields=product_name,brands,nutriscore_grade,allergens_tags,nutriments"
            + "&search_terms=";

    private static final long MIN_GAP_MS = 7_000;
    private static final Map<String, Nutrition> CACHE = new ConcurrentHashMap<>();
    private static final Object RATE_LOCK = new Object();
    private static long lastCallAt = 0;

    private FoodFactsClient() { }

    /**
     * @return the nutrition facts of the best matching product, or {@code null} if none was found.
     * @throws Exception when there is no internet connection or the reply cannot be parsed
     */
    public static Nutrition lookup(String ingredientName) throws Exception {
        String key = ingredientName.trim().toLowerCase(Locale.ROOT);
        Nutrition cached = CACHE.get(key);
        if (cached != null) {
            return cached == NOT_FOUND ? null : cached;
        }

        String body;
        synchronized (RATE_LOCK) {
            long wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt);
            if (wait > 0) Thread.sleep(wait);
            lastCallAt = System.currentTimeMillis();
            body = NetworkUtil.httpGet(SEARCH_URL + URLEncoder.encode(ingredientName.trim(), StandardCharsets.UTF_8), 20);
        }

        Nutrition best = parseBestProduct(body);
        CACHE.put(key, best == null ? NOT_FOUND : best);
        return best;
    }

    /** DATA PARSING: picks the first product that has a name AND an energy value. */
    static Nutrition parseBestProduct(String body) {
        JSONArray products = new JSONObject(body).optJSONArray("products");
        if (products == null) return null;

        for (int i = 0; i < products.length(); i++) {
            JSONObject product = products.getJSONObject(i);
            String name = product.optString("product_name", "").trim();
            JSONObject n = product.optJSONObject("nutriments");
            if (name.isEmpty() || n == null || !n.has("energy-kcal_100g")) continue;

            List<String> allergens = new ArrayList<>();
            JSONArray tags = product.optJSONArray("allergens_tags");
            if (tags != null) {
                for (int t = 0; t < tags.length(); t++) {
                    allergens.add(tags.getString(t).replaceFirst("^[a-z]{2}:", "").replace('-', ' '));
                }
            }
            String grade = product.optString("nutriscore_grade", "");
            return new Nutrition(name, product.optString("brands", ""),
                    number(n, "energy-kcal_100g"), number(n, "fat_100g"), number(n, "saturated-fat_100g"),
                    number(n, "carbohydrates_100g"), number(n, "sugars_100g"), number(n, "proteins_100g"),
                    number(n, "salt_100g"),
                    grade.matches("[a-eA-E]") ? grade : null, allergens);
        }
        return null;
    }

    private static Double number(JSONObject json, String key) {
        double value = json.optDouble(key, Double.NaN);
        return Double.isNaN(value) ? null : value;
    }
}
