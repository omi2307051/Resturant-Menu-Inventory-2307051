package com.restaurant.inventory.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MODEL: a menu item (named Dish so it does not clash with javafx.scene.control.MenuItem).
 * A dish knows which ingredients it needs, so it can tell whether it can be prepared.
 *
 * ADVANCED OOP: extends the abstract class {@link MenuItem} (inheritance - name/category/price
 * live in the parent) and implements the {@link Reportable} interface so it can be included,
 * polymorphically, alongside Ingredient and Person in the "Reports" feature.
 */
public class Dish extends MenuItem implements Reportable {

    /** Category names used everywhere (menu tree, colour theme, daily discount rules). */
    public static final String CAT_DESSERTS = "Desserts";

    // ---- full restaurant menu categories ----------------------------------------------
    public static final String CAT_BIRYANI = "Biryani & Rice";
    public static final String CAT_CHICKEN = "Chicken Specials";
    public static final String CAT_BEEF_MUTTON = "Beef & Mutton";
    public static final String CAT_BURGERS = "Burgers";
    public static final String CAT_PIZZA = "Pizza";
    public static final String CAT_CHINESE = "Chinese";
    public static final String CAT_APPETIZERS = "Appetizers & Snacks";
    public static final String CAT_SOUP = "Soup";
    public static final String CAT_MEXICAN = "Mexican & Fast Food";
    public static final String CAT_INTERNATIONAL = "International Specials";
    public static final String CAT_SALAD = "Salad";
    public static final String CAT_COLD_DRINKS = "Cold Drinks";
    public static final String CAT_MILKSHAKES = "Milkshakes & Special Drinks";
    public static final String CAT_HOT_BEVERAGES = "Hot Beverages";

    /** Every CSS style class returned by {@link #categoryStyleClass}, for controllers that need to clear them all at once. */
    public static final List<String> ALL_CATEGORY_STYLE_CLASSES = List.of(
            "cat-desserts", "cat-biryani", "cat-chicken", "cat-beefmutton", "cat-burgers",
            "cat-pizza", "cat-chinese", "cat-appetizers", "cat-soup", "cat-mexican",
            "cat-international", "cat-salad", "cat-colddrinks", "cat-milkshakes", "cat-hotbeverages");

    private String imageName;
    /** The picture this dish was created with (its correct image) - used to undo an accidental mismatch. */
    private final String defaultImageName;
    private final List<RecipeLine> recipe = new ArrayList<>();
    private boolean chefSpecial = false;

    public Dish(String name, String category, double price, String imageName) {
        super(name, category, price);
        this.imageName = imageName;
        this.defaultImageName = imageName;
    }

    /** Fluent helper: dish.needs(patty, 1).needs(bun, 1) ... */
    public Dish needs(Ingredient ingredient, double amountPerServing) {
        recipe.add(new RecipeLine(ingredient, amountPerServing));
        return this;
    }

    /** Fluent helper: marks this dish as a "Chef's Special" (shown with a star badge). */
    public Dish special() {
        this.chefSpecial = true;
        return this;
    }

    public String getImageName() { return imageName; }
    public void setImageName(String imageName) { this.imageName = imageName; }

    /** The correct, originally-assigned picture for this dish - never changes after construction. */
    public String getDefaultImageName() { return defaultImageName; }
    public List<RecipeLine> getRecipe() { return Collections.unmodifiableList(recipe); }
    public boolean isChefSpecial() { return chefSpecial; }

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

    /** CSS style class (see styles.css) used to give this dish's category its own colour theme. */
    public static String categoryStyleClass(String category) {
        if (category == null) return "cat-international";
        return switch (category) {
            case CAT_DESSERTS -> "cat-desserts";
            case CAT_BIRYANI -> "cat-biryani";
            case CAT_CHICKEN -> "cat-chicken";
            case CAT_BEEF_MUTTON -> "cat-beefmutton";
            case CAT_BURGERS -> "cat-burgers";
            case CAT_PIZZA -> "cat-pizza";
            case CAT_CHINESE -> "cat-chinese";
            case CAT_APPETIZERS -> "cat-appetizers";
            case CAT_SOUP -> "cat-soup";
            case CAT_MEXICAN -> "cat-mexican";
            case CAT_INTERNATIONAL -> "cat-international";
            case CAT_SALAD -> "cat-salad";
            case CAT_COLD_DRINKS -> "cat-colddrinks";
            case CAT_MILKSHAKES -> "cat-milkshakes";
            case CAT_HOT_BEVERAGES -> "cat-hotbeverages";
            default -> "cat-international";
        };
    }

    /** ABSTRACT METHOD IMPLEMENTATION (from MenuItem): how this item is labelled on screen. */
    @Override
    public String getDisplayLabel() {
        return isChefSpecial() ? "\u2B50 " + getName() : getName();
    }

    /** INTERFACE IMPLEMENTATION (Reportable): one-line summary used by the Reports feature. */
    @Override
    public String getSummary() {
        return String.format("[Dish] %-22s %-12s \u09F3%,.2f%s", getName(), getCategory(),
                getPrice(), isAvailable() ? "" : "  (OUT OF STOCK)");
    }

    @Override
    public String toString() { return getName(); }
}
