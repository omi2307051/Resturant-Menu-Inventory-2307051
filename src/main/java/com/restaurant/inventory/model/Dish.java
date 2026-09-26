package com.restaurant.inventory.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MODEL: a menu item (named Dish so it does not clash with javafx.scene.control.MenuItem).
 * A dish knows which ingredients it needs, so it can tell whether it can be prepared.
 */
public class Dish {

    /** Images stored in src/main/resources/images (used by the "Change Image" button). */
    public static final List<String> IMAGE_FILES = List.of(
            "burger.png", "pizza.png", "pasta.png", "salad.png",
            "soup.png", "juice.png", "dessert.png");

    private final String name;
    private final String category;
    private final double price;
    private String imageName;
    private final List<RecipeLine> recipe = new ArrayList<>();

    public Dish(String name, String category, double price, String imageName) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.imageName = imageName;
    }

    /** Fluent helper: dish.needs(patty, 1).needs(bun, 1) ... */
    public Dish needs(Ingredient ingredient, double amountPerServing) {
        recipe.add(new RecipeLine(ingredient, amountPerServing));
        return this;
    }

    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }
    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }
    public List<RecipeLine> getRecipe() { return Collections.unmodifiableList(recipe); }

    /** True if there is enough stock of EVERY ingredient for the given number of servings. */
    public boolean canMake(int servings) {
        return missingIngredients(servings).isEmpty();
    }

    /** True if at least one serving can be prepared. */
    public boolean isAvailable() {
        return canMake(1);
    }

    /** Human readable list of ingredients that are not enough, e.g. "Cheese Slice (need 2, have 1)". */
    public List<String> missingIngredients(int servings) {
        List<String> missing = new ArrayList<>();
        for (RecipeLine line : recipe) {
            double needed = line.getAmount() * servings;
            double have = line.getIngredient().getQuantity();
            if (have < needed) {
                missing.add(String.format("%s (need %s, have %s)",
                        line.getIngredient().getName(),
                        Ingredient.formatAmount(needed),
                        Ingredient.formatAmount(have)));
            }
        }
        return missing;
    }

    @Override
    public String toString() { return name; }
}
