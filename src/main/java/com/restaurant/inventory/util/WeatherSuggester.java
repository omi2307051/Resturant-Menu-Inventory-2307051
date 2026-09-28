package com.restaurant.inventory.util;

import com.restaurant.inventory.model.WeatherInfo;
import com.restaurant.inventory.model.WeatherOffer;

import java.util.List;

/**
 * Turns the weather into a food suggestion + discount. Pure Java (no JavaFX, no network),
 * so the rules are easy to read and change: edit the dish lists / percentages below.
 * Dish names must match the menu exactly; a name that is not on the menu is simply skipped.
 */
public final class WeatherSuggester {

    private WeatherSuggester() { }

    public static WeatherOffer suggest(WeatherInfo w) {
        return switch (w.mood()) {
            case RAINY -> new WeatherOffer(w.mood(), "Rainy Day Deal", 12,
                    "Rain outside - warm up with something hot and crispy!",
                    List.of("Masala Tea", "Milk Tea", "Chicken Samosa", "Chicken Spring Roll", "French Fries",
                            "Chicken Corn Soup", "Hot & Sour Soup", "Haleem", "Beef Nihari"));
            case HOT -> new WeatherOffer(w.mood(), "Cool Down Deal", 12,
                    "Hot and humid outside - cool down with something chilled!",
                    List.of("Mint Lemonade", "Fresh Lemonade", "Watermelon Juice", "Orange Juice",
                            "Mango Milkshake", "Cold Coffee", "Falooda", "Ice Cream", "Fruit Salad",
                            "Chocolate Sundae"));
            case COLD -> new WeatherOffer(w.mood(), "Warm-Up Deal", 12,
                    "Chilly weather - something hot and hearty is the best choice!",
                    List.of("Chicken Corn Soup", "Hot & Sour Soup", "Mushroom Soup", "Masala Tea", "Cappuccino",
                            "Haleem", "Beef Nihari", "Mutton Curry", "Chocolate Lava Cake"));
            case MILD -> new WeatherOffer(w.mood(), "Pleasant Day Pick", 8,
                    "Lovely weather - a good day for a proper restaurant meal!",
                    List.of("Kacchi Biryani", "Grilled Chicken", "BBQ Chicken Pizza", "Chicken Shawarma",
                            "Chicken Alfredo Pasta", "Fresh Lemonade", "Cheesecake"));
        };
    }
}
