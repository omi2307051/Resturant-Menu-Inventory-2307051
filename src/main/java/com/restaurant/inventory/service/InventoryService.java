package com.restaurant.inventory.service;

import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.model.Person;
import com.restaurant.inventory.model.RecipeLine;
import javafx.beans.Observable;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The "brain" of the application. One shared instance (singleton) holds all the data,
 * so every tab (controller) sees the same ingredients, dishes and staff.
 *
 * Core idea:  ordering a dish  ->  deduct its ingredients from stock  ->  show what is out of stock.
 */
public final class InventoryService {

    /** Result of trying to place an order. */
    public record OrderResult(boolean success, String message) { }

    private static final InventoryService INSTANCE = new InventoryService();

    public static InventoryService getInstance() {
        return INSTANCE;
    }

    // The "extractor" makes the list fire an update event whenever an ingredient's quantity changes.
    private final ObservableList<Ingredient> ingredients =
            FXCollections.observableArrayList(i -> new Observable[]{ i.quantityProperty() });
    private final ObservableList<Dish> dishes = FXCollections.observableArrayList();
    private final ObservableList<Person> staff = FXCollections.observableArrayList();
    private final ObservableList<String> orderHistory = FXCollections.observableArrayList();

    private final ReadOnlyStringWrapper stockAlert = new ReadOnlyStringWrapper();
    private final IntegerProperty stockVersion = new SimpleIntegerProperty(0);

    private InventoryService() {
        seedData();
        ingredients.addListener((ListChangeListener<Ingredient>) change -> updateAlert());
        updateAlert();
    }

    // ------------------------------------------------------------------ getters

    public ObservableList<Ingredient> getIngredients() { return ingredients; }
    public ObservableList<Dish> getDishes() { return dishes; }
    public ObservableList<Person> getStaff() { return staff; }
    public ObservableList<String> getOrderHistory() { return orderHistory; }

    /** Text such as "OUT OF STOCK ingredients: Cheese Slice | Cannot be ordered right now: Cheeseburger". */
    public ReadOnlyStringProperty stockAlertProperty() { return stockAlert.getReadOnlyProperty(); }
    public String getStockAlertText() { return stockAlert.get(); }

    /** Increases every time any stock level changes - controllers listen to refresh their lists. */
    public IntegerProperty stockVersionProperty() { return stockVersion; }

    public Dish findDish(String name) {
        return dishes.stream().filter(d -> d.getName().equals(name)).findFirst().orElse(null);
    }

    public Ingredient findIngredient(String name) {
        return ingredients.stream().filter(i -> i.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ core logic

    /**
     * Try to prepare 'quantity' servings of a dish.
     * If every ingredient is available -> deduct them from stock and log the order.
     * Otherwise -> change nothing and explain what is missing.
     */
    public OrderResult placeOrder(Dish dish, int quantity) {
        List<String> missing = dish.missingIngredients(quantity);
        if (!missing.isEmpty()) {
            return new OrderResult(false, "Cannot prepare " + quantity + " x " + dish.getName()
                    + ". Not enough: " + String.join(", ", missing));
        }

        for (RecipeLine line : dish.getRecipe()) {
            line.getIngredient().deduct(line.getAmount() * quantity);
        }

        double total = dish.getPrice() * quantity;
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        orderHistory.add(0, String.format("%s   %d x %s   ($%.2f)", time, quantity, dish.getName(), total));

        return new OrderResult(true, String.format("Order placed: %d x %s  -  total $%.2f. Ingredients deducted from stock.",
                quantity, dish.getName(), total));
    }

    /** Puts every ingredient back to its starting quantity and clears the order history. */
    public void resetToDefaults() {
        for (Ingredient ingredient : ingredients) {
            ingredient.setQuantity(ingredient.getDefaultQuantity());
        }
        orderHistory.clear();
        updateAlert();
    }

    /**
     * Loads stock levels from a CSV/text file. Each line: name,quantity[,unit]
     * Lines starting with # and lines that are not numbers (e.g. a header) are skipped.
     * Existing ingredients are updated, unknown ones are added.
     */
    public int loadStockFromCsv(File file) throws IOException {
        int count = 0;
        for (String rawLine : Files.readAllLines(file.toPath())) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split(",");
            if (parts.length < 2) continue;

            double qty;
            try {
                qty = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                continue; // header line or bad value
            }

            String name = parts[0].trim();
            String unit = parts.length > 2 ? parts[2].trim() : "pcs";
            Ingredient existing = findIngredient(name);
            if (existing != null) {
                existing.setQuantity(qty);
            } else {
                ingredients.add(new Ingredient(name, unit, qty, 0));
            }
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    private void updateAlert() {
        String outOfStock = ingredients.stream()
                .filter(Ingredient::isOutOfStock)
                .map(Ingredient::getName)
                .collect(Collectors.joining(", "));
        String unavailable = dishes.stream()
                .filter(d -> !d.isAvailable())
                .map(Dish::getName)
                .collect(Collectors.joining(", "));

        List<String> parts = new ArrayList<>();
        if (!outOfStock.isEmpty()) parts.add("OUT OF STOCK ingredients: " + outOfStock);
        if (!unavailable.isEmpty()) parts.add("Cannot be ordered right now: " + unavailable);

        stockAlert.set(parts.isEmpty()
                ? "All good - every dish can be ordered."
                : String.join("   |   ", parts));
        stockVersion.set(stockVersion.get() + 1);
    }

    private Ingredient stock(String name, String unit, double qty, double minLevel) {
        Ingredient ingredient = new Ingredient(name, unit, qty, minLevel);
        ingredients.add(ingredient);
        return ingredient;
    }

    /** Sample data so the app is useful the first time you run it. */
    private void seedData() {
        Ingredient patty      = stock("Beef Patty",   "pcs", 6,    2);
        Ingredient bun        = stock("Burger Bun",   "pcs", 10,   2);
        Ingredient cheese     = stock("Cheese Slice", "pcs", 12,   3);
        Ingredient lettuce    = stock("Lettuce",      "g",   800,  150);
        Ingredient tomato     = stock("Tomato",       "g",   1500, 300);
        Ingredient cucumber   = stock("Cucumber",     "g",   600,  150);
        Ingredient dough      = stock("Pizza Dough",  "pcs", 8,    2);
        Ingredient mozzarella = stock("Mozzarella",   "g",   1200, 300);
        Ingredient sauce      = stock("Tomato Sauce", "ml",  1000, 200);
        Ingredient spaghetti  = stock("Spaghetti",    "g",   2000, 400);
        Ingredient chicken    = stock("Chicken",      "g",   1500, 300);
        Ingredient cream      = stock("Cream",        "ml",  1200, 250);
        Ingredient milk       = stock("Milk",         "ml",  3000, 600);
        Ingredient orange     = stock("Orange",       "pcs", 12,   3);
        Ingredient mango      = stock("Mango",        "pcs", 5,    2);
        Ingredient apple      = stock("Apple",        "pcs", 10,   3);

        dishes.add(new Dish("Garden Salad", "Starters", 6.00, "salad.png")
                .needs(lettuce, 100).needs(tomato, 80).needs(cucumber, 60));
        dishes.add(new Dish("Tomato Soup", "Starters", 5.00, "soup.png")
                .needs(tomato, 200).needs(cream, 30));

        dishes.add(new Dish("Cheeseburger", "Main Course", 8.50, "burger.png")
                .needs(patty, 1).needs(bun, 1).needs(cheese, 1).needs(lettuce, 30).needs(tomato, 40));
        dishes.add(new Dish("Margherita Pizza", "Main Course", 10.00, "pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80));
        dishes.add(new Dish("Chicken Alfredo Pasta", "Main Course", 11.50, "pasta.png")
                .needs(spaghetti, 200).needs(chicken, 150).needs(cream, 100));

        dishes.add(new Dish("Orange Juice", "Drinks", 4.00, "juice.png")
                .needs(orange, 3));
        dishes.add(new Dish("Mango Shake", "Drinks", 4.50, "juice.png")
                .needs(mango, 2).needs(milk, 250));

        dishes.add(new Dish("Fruit Salad", "Desserts", 5.50, "dessert.png")
                .needs(mango, 1).needs(orange, 1).needs(apple, 1));

        staff.add(new Person("Rahim Uddin", "Male", "Expert", "Bangladesh",
                LocalDate.of(1985, 4, 12), "Reading, Traveling", "-"));
        staff.add(new Person("Ayesha Rahman", "Female", "Intermediate", "Bangladesh",
                LocalDate.of(1994, 9, 3), "Gaming", "-"));
        staff.add(new Person("John Smith", "Male", "Beginner", "United Kingdom",
                LocalDate.of(2001, 1, 20), "Traveling", "-"));
    }
}
