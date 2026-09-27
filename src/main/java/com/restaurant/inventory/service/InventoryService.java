package com.restaurant.inventory.service;

import com.restaurant.inventory.model.CartLine;
import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.model.Person;
import com.restaurant.inventory.model.RecipeLine;
import com.restaurant.inventory.model.Reportable;
import com.restaurant.inventory.util.DatabaseManager;
import com.restaurant.inventory.util.NetworkUtil;
import javafx.beans.Observable;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The "brain" of the application. One shared instance (singleton) holds all the data,
 * so every tab (controller) sees the same ingredients, dishes and staff.
 *
 * Core idea:  ordering dish(es)  ->  deduct ingredients from stock  ->  show what is out of stock.
 * Prices are shown in Bangladeshi Taka (৳), and every category gets an automatic discount
 * on a different day of the week (see DAILY_DISCOUNTS).
 */
public final class InventoryService {

    /** Result of trying to place an order. */
    public record OrderResult(boolean success, String message) { }

    /** One day's promotion: which category is discounted, by how much, and its promo name. */
    public record DiscountRule(String category, int percent, String promoName) { }

    private static final InventoryService INSTANCE = new InventoryService();

    public static InventoryService getInstance() {
        return INSTANCE;
    }

    /** "ALL" means every category is discounted that day, not just one. */
    private static final String ALL_CATEGORIES = "ALL";

    /**
     * A different category (or comma-separated set of categories) is on special offer every
     * day of the week, matching the restaurant's own "Weekly Special Offers" board.
     */
    private static final Map<DayOfWeek, DiscountRule> DAILY_DISCOUNTS = Map.of(
            DayOfWeek.MONDAY,    new DiscountRule(Dish.CAT_BURGERS,                              15, "Burger Monday"),
            DayOfWeek.TUESDAY,   new DiscountRule(Dish.CAT_CHICKEN,                               10, "Chicken Tuesday"),
            DayOfWeek.WEDNESDAY, new DiscountRule(Dish.CAT_PIZZA,                                 15, "Pizza Wednesday"),
            DayOfWeek.THURSDAY,  new DiscountRule(Dish.CAT_INTERNATIONAL,                         17, "International Thursday"),
            DayOfWeek.FRIDAY,    new DiscountRule(ALL_CATEGORIES,                                 10, "Friday Feast"),
            DayOfWeek.SATURDAY,  new DiscountRule(Dish.CAT_CHINESE + "," + Dish.CAT_MEXICAN,       10, "Chinese & Mexican Saturday"),
            DayOfWeek.SUNDAY,    new DiscountRule(Dish.CAT_DESSERTS,                               20, "Dessert Sunday")
    );

    // The "extractor" makes the list fire an update event whenever an ingredient's quantity changes.
    private final ObservableList<Ingredient> ingredients =
            FXCollections.observableArrayList(i -> new Observable[]{ i.quantityProperty() });
    private final ObservableList<Dish> dishes = FXCollections.observableArrayList();
    private final ObservableList<Person> staff = FXCollections.observableArrayList();
    private final ObservableList<String> orderHistory = FXCollections.observableArrayList();

    private final ReadOnlyStringWrapper stockAlert = new ReadOnlyStringWrapper();
    private final IntegerProperty stockVersion = new SimpleIntegerProperty(0);
    private final DoubleProperty todaysRevenue = new SimpleDoubleProperty(0);

    /**
     * CONCURRENCY: a small thread pool shared by the whole app for anything that must not
     * block the JavaFX UI thread (checking a "supplier" price, fetching a live exchange
     * rate, or parsing a big CSV file). Daemon threads so the pool never keeps the app alive.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "restaurant-worker");
        thread.setDaemon(true);
        return thread;
    });

    private InventoryService() {
        DatabaseManager.initSchema();
        seedData();
        seedStaffIfEmpty();
        ingredients.addListener((ListChangeListener<Ingredient>) change -> updateAlert());
        updateAlert();
    }

    /** The shared background thread pool (CONCURRENCY topic). */
    public ExecutorService getExecutor() { return executor; }

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

    /** Running total of everything sold "today" (since launch or the last File > New). */
    public DoubleProperty todaysRevenueProperty() { return todaysRevenue; }
    public double getTodaysRevenue() { return todaysRevenue.get(); }

    public Dish findDish(String name) {
        return dishes.stream().filter(d -> d.getName().equals(name)).findFirst().orElse(null);
    }

    /**
     * Finds a dish by name, typed by the customer - this is how "order by name" works.
     * Tries an exact match first, then "starts with", then "contains", so a partial
     * name such as "mango" or "sushi" is enough to find the right dish.
     */
    public Dish findDishByNameLoose(String text) {
        if (text == null) return null;
        String query = text.trim().toLowerCase();
        if (query.isEmpty()) return null;

        for (Dish d : dishes) {
            if (d.getName().equalsIgnoreCase(query)) return d;
        }
        for (Dish d : dishes) {
            if (d.getName().toLowerCase().startsWith(query)) return d;
        }
        for (Dish d : dishes) {
            if (d.getName().toLowerCase().contains(query)) return d;
        }
        return null;
    }

    public Ingredient findIngredient(String name) {
        return ingredients.stream().filter(i -> i.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ CURRENCY (Bangladeshi Taka)

    /** Formats an amount as Bangladeshi Taka, e.g. 125.5 -> "\u09F3125.50". */
    public static String taka(double amount) {
        return String.format("\u09F3%,.2f", amount);
    }

    // ------------------------------------------------------------------ DAILY DISCOUNTS

    /** Today's promotion (never null - every day of the week has a rule). */
    public DiscountRule getTodayDiscountRule() {
        return DAILY_DISCOUNTS.get(LocalDate.now().getDayOfWeek());
    }

    /** Discount percentage (0-100) that applies to this dish today. */
    public int getDiscountPercent(Dish dish) {
        DiscountRule rule = getTodayDiscountRule();
        if (rule == null || dish == null) return 0;
        if (rule.category().equals(ALL_CATEGORIES)) {
            return rule.percent();
        }
        // A rule's category can be a comma-separated list (e.g. Saturday: "Chinese,Mexican & Fast Food").
        for (String cat : rule.category().split("\\s*,\\s*")) {
            if (cat.equalsIgnoreCase(dish.getCategory())) {
                return rule.percent();
            }
        }
        return 0;
    }

    /** True if today's promotion applies to this dish. */
    public boolean hasDiscountToday(Dish dish) {
        return getDiscountPercent(dish) > 0;
    }

    /** Price of one serving after today's discount is applied. */
    public double getDiscountedPrice(Dish dish) {
        int percent = getDiscountPercent(dish);
        return dish.getPrice() * (100 - percent) / 100.0;
    }

    /** Banner text, e.g. "Sweet Tooth Thursday - 25% OFF all Desserts today!" */
    public String getTodayDiscountText() {
        DiscountRule rule = getTodayDiscountRule();
        if (rule == null) return "";
        String target = rule.category().equals(ALL_CATEGORIES) ? "every dish" : "all " + rule.category().replace(",", " & ");
        String today = LocalDate.now().getDayOfWeek().toString();
        today = today.charAt(0) + today.substring(1).toLowerCase();
        return String.format("%s (%s): %d%% OFF %s!", rule.promoName(), today, rule.percent(), target);
    }

    // ------------------------------------------------------------------ SINGLE-DISH ORDER

    /**
     * Try to prepare 'quantity' servings of ONE dish (today's discount is applied automatically).
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
            Ingredient ingredient = line.getIngredient();
            ingredient.deduct(line.getAmount() * quantity);
            DatabaseManager.upsertIngredient(ingredient.getName(), ingredient.getUnit(),
                    ingredient.getQuantity(), ingredient.getMinLevel());
        }

        double total = getDiscountedPrice(dish) * quantity;
        todaysRevenue.set(todaysRevenue.get() + total);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String summary = String.format("%d x %s", quantity, dish.getName());
        orderHistory.add(0, String.format("%s   %s   (%s)", time, summary, taka(total)));
        DatabaseManager.insertOrder(summary, total, Instant.now().toString());

        return new OrderResult(true, String.format("Order placed: %d x %s  -  total %s. Ingredients deducted from stock.",
                quantity, dish.getName(), taka(total)));
    }

    // ------------------------------------------------------------------ MULTI-DISH ORDER (CART)

    /**
     * Places an entire cart of dishes (several different dishes, each found by name)
     * as ONE transaction: every line must be available, otherwise nothing at all is
     * deducted. Today's per-category discount is applied to every line automatically.
     */
    public OrderResult placeCartOrder(List<CartLine> cart) {
        if (cart == null || cart.isEmpty()) {
            return new OrderResult(false, "Your cart is empty. Type a dish name and click \"Add to Cart\" first.");
        }

        List<String> problems = new ArrayList<>();
        for (CartLine line : cart) {
            List<String> missing = line.getDish().missingIngredients(line.getQuantity());
            if (!missing.isEmpty()) {
                problems.add(line.getDish().getName() + ": " + String.join(", ", missing));
            }
        }
        if (!problems.isEmpty()) {
            return new OrderResult(false, "Cannot place this order - not enough stock for:\n"
                    + String.join("\n", problems));
        }

        double grandTotal = 0;
        StringBuilder receipt = new StringBuilder();
        int dishCount = 0;
        for (CartLine line : cart) {
            Dish dish = line.getDish();
            int qty = line.getQuantity();
            for (RecipeLine recipeLine : dish.getRecipe()) {
                Ingredient ingredient = recipeLine.getIngredient();
                ingredient.deduct(recipeLine.getAmount() * qty);
                DatabaseManager.upsertIngredient(ingredient.getName(), ingredient.getUnit(),
                        ingredient.getQuantity(), ingredient.getMinLevel());
            }
            double unitPrice = getDiscountedPrice(dish);
            double lineTotal = unitPrice * qty;
            grandTotal += lineTotal;
            dishCount += qty;
            receipt.append(String.format("  %d x %-24s %s%n", qty, dish.getName(), taka(lineTotal)));
        }

        todaysRevenue.set(todaysRevenue.get() + grandTotal);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String summary = String.format("%d dish(es), %d item(s)", cart.size(), dishCount);
        orderHistory.add(0, String.format("%s   %s   Total %s", time, summary, taka(grandTotal)));
        DatabaseManager.insertOrder(summary, grandTotal, Instant.now().toString());

        return new OrderResult(true, "Order placed! Ingredients deducted from stock.\n\n"
                + receipt + "\nGrand total: " + taka(grandTotal));
    }

    /** Puts every ingredient back to its starting quantity and clears the order history + today's sales. */
    public void resetToDefaults() {
        for (Ingredient ingredient : ingredients) {
            ingredient.setQuantity(ingredient.getDefaultQuantity());
            DatabaseManager.upsertIngredient(ingredient.getName(), ingredient.getUnit(),
                    ingredient.getQuantity(), ingredient.getMinLevel());
        }
        orderHistory.clear();
        todaysRevenue.set(0);
        updateAlert();
    }

    /**
     * CONCURRENCY-SAFE STEP 1 (runs on a background thread): reads and parses the CSV/text file
     * ONLY - it never touches the ObservableList (JavaFX collections must only be changed on the
     * UI thread), so this method is safe to call from inside a {@link Task}.
     * Each line: name,quantity[,unit]. Lines starting with # and non-numeric lines (e.g. a header)
     * are skipped.
     */
    public List<Object[]> parseStockCsv(File file) throws IOException {
        List<Object[]> parsed = new ArrayList<>();
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
            parsed.add(new Object[]{ name, qty, unit });
        }
        return parsed;
    }

    /**
     * CONCURRENCY-SAFE STEP 2 (must run on the JavaFX UI thread, e.g. from a Task's
     * onSucceeded handler): applies already-parsed rows to the ObservableList and persists
     * them. Existing ingredients are updated, unknown ones are added.
     */
    public int applyParsedStock(List<Object[]> parsedRows) {
        int count = 0;
        for (Object[] row : parsedRows) {
            String name = (String) row[0];
            double qty = (double) row[1];
            String unit = (String) row[2];

            Ingredient existing = findIngredient(name);
            if (existing != null) {
                existing.setQuantity(qty);
                DatabaseManager.upsertIngredient(existing.getName(), existing.getUnit(), qty, existing.getMinLevel());
            } else {
                Ingredient added = new Ingredient(name, unit, qty, 0);
                ingredients.add(added);
                DatabaseManager.upsertIngredient(name, unit, qty, 0);
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
        // DATABASE INTEGRATION: if this ingredient was already saved from an earlier run,
        // start from its persisted quantity instead of the hard-coded default, so stock
        // levels survive an application restart. Otherwise, save the default now.
        Double persisted = DatabaseManager.readIngredientQuantity(name);
        double startQty = persisted != null ? persisted : qty;

        Ingredient ingredient = new Ingredient(name, unit, startQty, minLevel);
        ingredients.add(ingredient);
        DatabaseManager.upsertIngredient(name, unit, startQty, minLevel);
        return ingredient;
    }



    /** Sample data so the app is useful the first time you run it. */
    private void seedData() {
        // ---- ingredients --------------------------------------------------------------
        Ingredient patty      = stock("Beef Patty",      "pcs", 6,    2);
        Ingredient bun         = stock("Burger Bun",      "pcs", 10,   2);
        Ingredient cheese      = stock("Cheese Slice",    "pcs", 12,   3);
        Ingredient lettuce     = stock("Lettuce",         "g",   800,  150);
        Ingredient tomato      = stock("Tomato",          "g",   1500, 300);
        Ingredient cucumber    = stock("Cucumber",        "g",   600,  150);
        Ingredient dough       = stock("Pizza Dough",     "pcs", 8,    2);
        Ingredient mozzarella  = stock("Mozzarella",      "g",   1200, 300);
        Ingredient sauce       = stock("Tomato Sauce",    "ml",  1000, 200);
        Ingredient spaghetti   = stock("Spaghetti",       "g",   2000, 400);
        Ingredient chicken     = stock("Chicken",         "g",   1500, 300);
        Ingredient cream       = stock("Cream",           "ml",  1200, 250);
        Ingredient milk        = stock("Milk",            "ml",  3000, 600);
        Ingredient orange      = stock("Orange",          "pcs", 12,   3);
        Ingredient mango       = stock("Mango",           "pcs", 5,    2);
        Ingredient apple       = stock("Apple",           "pcs", 10,   3);
        Ingredient butter      = stock("Butter",          "g",   500,  100);

        // ---- new ingredients for appetizers / desserts / foreign dishes ---------------
        Ingredient cabbage       = stock("Cabbage",            "g",   500,  100);
        Ingredient carrot        = stock("Carrot",             "g",   500,  100);
        Ingredient rollWrapper   = stock("Spring Roll Wrapper", "pcs", 24,   6);
        Ingredient chickenWing   = stock("Chicken Wings",       "pcs", 24,   6);
        Ingredient wingSauce     = stock("Wing Sauce",          "ml",  400,  80);
        Ingredient potato        = stock("Potato",             "g",   2000, 300);
        Ingredient greenPea      = stock("Green Peas",          "g",   400,  80);
        Ingredient samosaWrapper = stock("Samosa Wrapper",      "pcs", 18,   6);
        Ingredient flour         = stock("Flour",               "g",   1500, 300);
        Ingredient chocolate     = stock("Chocolate",           "g",   600,  120);
        Ingredient egg           = stock("Egg",                 "pcs", 24,   6);
        Ingredient iceCream      = stock("Ice Cream",           "g",   1200, 250);
        Ingredient chocSyrup     = stock("Chocolate Syrup",     "ml",  400,  80);
        Ingredient nuts          = stock("Mixed Nuts",          "g",   300,  60);
        Ingredient milkPowder    = stock("Milk Powder",         "g",   500,  100);
        Ingredient sugarSyrup    = stock("Sugar Syrup",         "ml",  500,  100);
        Ingredient ghee          = stock("Ghee",                "g",   300,  60);
        Ingredient sushiRice     = stock("Sushi Rice",          "g",   1200, 250);
        Ingredient nori          = stock("Nori Sheet",          "pcs", 20,   5);
        Ingredient salmon        = stock("Salmon",              "g",   800,  150);
        Ingredient wasabi        = stock("Wasabi",              "g",   100,  20);
        Ingredient beefCut       = stock("Beef Steak Cut",      "g",   1500, 300);
        Ingredient blackPepper   = stock("Black Pepper",        "g",   150,  30);
        Ingredient coconutMilk   = stock("Coconut Milk",        "ml",  1000, 200);
        Ingredient curryPaste    = stock("Green Curry Paste",   "g",   300,  60);
        Ingredient tortilla      = stock("Tortilla",            "pcs", 18,   6);
        Ingredient salsa         = stock("Salsa",               "ml",  500,  100);
        Ingredient cheddar       = stock("Cheddar Cheese",      "g",   500,  100);

        // ---- new ingredients for the expanded Bangladeshi menu (chef's-special dishes) -
        Ingredient basmatiRice   = stock("Basmati Rice",         "g",   3000, 500);
        Ingredient muttonMeat    = stock("Mutton",               "g",   1500, 300);
        Ingredient yogurt        = stock("Yogurt",               "ml",  1000, 200);
        Ingredient onion         = stock("Onion",                "g",   1500, 300);
        Ingredient ramenNoodles  = stock("Ramen Noodles",        "g",   1200, 250);
        Ingredient soySauce      = stock("Soy Sauce",            "ml",  600,  120);
        Ingredient kunafaDough   = stock("Kunafa Dough",         "g",   500,  100);

        // ---- STARTERS -------------------------------------------------------------------
        dishes.add(new Dish("Garden Salad", Dish.CAT_STARTERS, 180.00, "salad.png")
                .needs(lettuce, 100).needs(tomato, 80).needs(cucumber, 60));
        dishes.add(new Dish("Tomato Soup", Dish.CAT_STARTERS, 150.00, "soup.png")
                .needs(tomato, 200).needs(cream, 30));
        dishes.add(new Dish("Spring Rolls", Dish.CAT_STARTERS, 160.00, "spring_rolls.png")
                .needs(cabbage, 80).needs(carrot, 40).needs(rollWrapper, 4));
        dishes.add(new Dish("Chicken Wings", Dish.CAT_STARTERS, 220.00, "chicken_wings.png")
                .needs(chickenWing, 6).needs(wingSauce, 40));
        dishes.add(new Dish("Vegetable Samosa", Dish.CAT_STARTERS, 130.00, "veg_samosa.png")
                .needs(potato, 120).needs(greenPea, 40).needs(samosaWrapper, 3));

        // ---- MAIN COURSE ------------------------------------------------------------------
        dishes.add(new Dish("Cheeseburger", Dish.CAT_MAIN, 320.00, "burger.png")
                .needs(patty, 1).needs(bun, 1).needs(cheese, 1).needs(lettuce, 30).needs(tomato, 40));
        dishes.add(new Dish("Margherita Pizza", Dish.CAT_MAIN, 420.00, "pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80));
        dishes.add(new Dish("Chicken Alfredo Pasta", Dish.CAT_MAIN, 380.00, "pasta.png")
                .needs(spaghetti, 200).needs(chicken, 150).needs(cream, 100).special());

        // ---- FOREIGN DISHES ---------------------------------------------------------------
        dishes.add(new Dish("Sushi Platter", Dish.CAT_FOREIGN, 480.00, "sushi_platter.png")
                .needs(sushiRice, 150).needs(nori, 3).needs(salmon, 100).needs(wasabi, 5).special());
        dishes.add(new Dish("Beef Steak", Dish.CAT_FOREIGN, 650.00, "beef_steak.png")
                .needs(beefCut, 250).needs(butter, 20).needs(blackPepper, 5));
        dishes.add(new Dish("Thai Green Curry", Dish.CAT_FOREIGN, 400.00, "thai_curry.png")
                .needs(chicken, 200).needs(coconutMilk, 200).needs(curryPaste, 40));
        dishes.add(new Dish("Mexican Tacos", Dish.CAT_FOREIGN, 350.00, "mexican_tacos.png")
                .needs(tortilla, 3).needs(beefCut, 120).needs(salsa, 60).needs(cheddar, 40));

        // ---- DRINKS -------------------------------------------------------------------------
        dishes.add(new Dish("Orange Juice", Dish.CAT_DRINKS, 130.00, "juice.png")
                .needs(orange, 3));
        dishes.add(new Dish("Mango Shake", Dish.CAT_DRINKS, 150.00, "mango_shake.png")
                .needs(mango, 2).needs(milk, 250).special());

        // ---- DESSERTS -----------------------------------------------------------------------
        dishes.add(new Dish("Fruit Salad", Dish.CAT_DESSERTS, 170.00, "dessert.png")
                .needs(mango, 1).needs(orange, 1).needs(apple, 1));
        dishes.add(new Dish("Chocolate Brownie", Dish.CAT_DESSERTS, 190.00, "brownie.png")
                .needs(flour, 80).needs(chocolate, 60).needs(butter, 30).needs(egg, 1));
        dishes.add(new Dish("Ice Cream Sundae", Dish.CAT_DESSERTS, 210.00, "sundae.png")
                .needs(iceCream, 150).needs(chocSyrup, 30).needs(nuts, 15));
        dishes.add(new Dish("Gulab Jamun", Dish.CAT_DESSERTS, 140.00, "gulab_jamun.png")
                .needs(milkPowder, 100).needs(sugarSyrup, 80).needs(ghee, 20));

        // Extra dessert options from the full menu (simple - no recipe/inventory tracking).
        dishes.add(new Dish("Cheesecake", Dish.CAT_DESSERTS, 250.00, "dessert.png"));
        dishes.add(new Dish("Red Velvet Cake", Dish.CAT_DESSERTS, 180.00, "brownie.png"));
        dishes.add(new Dish("Falooda", Dish.CAT_DESSERTS, 180.00, "sundae.png"));
        dishes.add(new Dish("Chocolate Lava Cake", Dish.CAT_DESSERTS, 220.00, "brownie.png")
                .needs(chocolate, 80).needs(flour, 50).needs(butter, 40).needs(egg, 2).special());

        // ============================ EXPANDED MENU (customer-supplied menu) ==============
        // Trimmed to ~4-6 items per section. Popular / Chef's Special dishes get a real
        // recipe (so ordering them deducts stock, like the sample dishes above); the rest
        // are "simple" dishes with no recipe, so they are always orderable (no inventory
        // tracking needed for every single item on a 200+ item menu).

        // ---- BIRYANI & RICE ---------------------------------------------------------------
        dishes.add(new Dish("Chicken Biryani", Dish.CAT_BIRYANI, 250.00, "thai_curry.png"));
        dishes.add(new Dish("Kacchi Biryani", Dish.CAT_BIRYANI, 320.00, "thai_curry.png")
                .needs(basmatiRice, 200).needs(muttonMeat, 200).needs(yogurt, 50).needs(onion, 30).special());
        dishes.add(new Dish("Beef Tehari", Dish.CAT_BIRYANI, 260.00, "thai_curry.png"));
        dishes.add(new Dish("Chicken Fried Rice", Dish.CAT_BIRYANI, 220.00, "thai_curry.png"));
        dishes.add(new Dish("Plain Rice", Dish.CAT_BIRYANI, 80.00, "thai_curry.png"));

        // ---- CHICKEN SPECIALS ---------------------------------------------------------------
        dishes.add(new Dish("Grilled Chicken", Dish.CAT_CHICKEN, 320.00, "chicken_wings.png"));
        dishes.add(new Dish("BBQ Chicken", Dish.CAT_CHICKEN, 350.00, "chicken_wings.png"));
        dishes.add(new Dish("Chicken Tandoori", Dish.CAT_CHICKEN, 300.00, "chicken_wings.png"));
        dishes.add(new Dish("Chicken Roast", Dish.CAT_CHICKEN, 280.00, "chicken_wings.png"));
        dishes.add(new Dish("Crispy Fried Chicken", Dish.CAT_CHICKEN, 220.00, "chicken_wings.png"));

        // ---- BEEF & MUTTON ---------------------------------------------------------------
        dishes.add(new Dish("Beef Kala Bhuna", Dish.CAT_BEEF_MUTTON, 350.00, "beef_steak.png"));
        dishes.add(new Dish("Beef Curry", Dish.CAT_BEEF_MUTTON, 280.00, "beef_steak.png"));
        dishes.add(new Dish("Mutton Curry", Dish.CAT_BEEF_MUTTON, 350.00, "beef_steak.png"));
        dishes.add(new Dish("Mutton Rezala", Dish.CAT_BEEF_MUTTON, 360.00, "beef_steak.png"));
        dishes.add(new Dish("Mutton Korma", Dish.CAT_BEEF_MUTTON, 380.00, "beef_steak.png"));

        // ---- BURGERS ---------------------------------------------------------------------
        dishes.add(new Dish("Crispy Chicken Burger", Dish.CAT_BURGERS, 250.00, "burger.png"));
        dishes.add(new Dish("BBQ Chicken Burger", Dish.CAT_BURGERS, 280.00, "burger.png"));
        dishes.add(new Dish("Beef Cheese Burger", Dish.CAT_BURGERS, 320.00, "burger.png"));
        dishes.add(new Dish("Double Beef Burger", Dish.CAT_BURGERS, 380.00, "burger.png"));
        dishes.add(new Dish("Special House Burger", Dish.CAT_BURGERS, 420.00, "burger.png")
                .needs(patty, 2).needs(bun, 1).needs(cheese, 2).needs(lettuce, 30).needs(tomato, 40).needs(onion, 20).special());

        // ---- PIZZA -------------------------------------------------------------------------
        dishes.add(new Dish("Chicken Pizza", Dish.CAT_PIZZA, 420.00, "pizza.png"));
        dishes.add(new Dish("BBQ Chicken Pizza", Dish.CAT_PIZZA, 480.00, "pizza.png"));
        dishes.add(new Dish("Pepperoni Pizza", Dish.CAT_PIZZA, 500.00, "pizza.png"));
        dishes.add(new Dish("Seafood Pizza", Dish.CAT_PIZZA, 550.00, "pizza.png"));
        dishes.add(new Dish("Special House Pizza", Dish.CAT_PIZZA, 600.00, "pizza.png")
                .needs(dough, 1).needs(mozzarella, 200).needs(sauce, 100).needs(cheddar, 50).needs(chicken, 100).special());

        // ---- CHINESE -----------------------------------------------------------------------
        dishes.add(new Dish("Chicken Chow Mein", Dish.CAT_CHINESE, 220.00, "pasta.png"));
        dishes.add(new Dish("Beef Chow Mein", Dish.CAT_CHINESE, 260.00, "pasta.png"));
        dishes.add(new Dish("Chicken Chilli", Dish.CAT_CHINESE, 280.00, "pasta.png"));
        dishes.add(new Dish("Chicken Szechuan", Dish.CAT_CHINESE, 300.00, "pasta.png"));
        dishes.add(new Dish("Chinese Mixed Platter", Dish.CAT_CHINESE, 450.00, "pasta.png"));

        // ---- APPETIZERS & SNACKS -----------------------------------------------------------
        dishes.add(new Dish("French Fries", Dish.CAT_APPETIZERS, 70.00, "veg_samosa.png"));
        dishes.add(new Dish("Cheese Fries", Dish.CAT_APPETIZERS, 150.00, "veg_samosa.png"));
        dishes.add(new Dish("Chicken Nuggets", Dish.CAT_APPETIZERS, 180.00, "veg_samosa.png"));
        dishes.add(new Dish("Garlic Bread", Dish.CAT_APPETIZERS, 110.00, "veg_samosa.png"));
        dishes.add(new Dish("Nachos", Dish.CAT_APPETIZERS, 250.00, "veg_samosa.png"));

        // ---- SOUP ----------------------------------------------------------------------------
        dishes.add(new Dish("Chicken Corn Soup", Dish.CAT_SOUP, 180.00, "soup.png"));
        dishes.add(new Dish("Hot & Sour Soup", Dish.CAT_SOUP, 200.00, "soup.png"));
        dishes.add(new Dish("Thai Soup", Dish.CAT_SOUP, 220.00, "soup.png"));
        dishes.add(new Dish("Cream of Chicken Soup", Dish.CAT_SOUP, 220.00, "soup.png"));

        // ---- MEXICAN & FAST FOOD -------------------------------------------------------------
        dishes.add(new Dish("Chicken Wrap", Dish.CAT_MEXICAN, 220.00, "mexican_tacos.png"));
        dishes.add(new Dish("Chicken Shawarma", Dish.CAT_MEXICAN, 180.00, "mexican_tacos.png"));
        dishes.add(new Dish("Beef Quesadilla", Dish.CAT_MEXICAN, 320.00, "mexican_tacos.png"));
        dishes.add(new Dish("Chicken Burrito", Dish.CAT_MEXICAN, 280.00, "mexican_tacos.png"));
        dishes.add(new Dish("Beef Sub Sandwich", Dish.CAT_MEXICAN, 280.00, "mexican_tacos.png"));

        // ---- INTERNATIONAL SPECIALS (Thai / Turkish / Arabian / Pakistani / Indian / Japanese) --
        dishes.add(new Dish("Chicken Kabsa", Dish.CAT_INTERNATIONAL, 420.00, "beef_steak.png")
                .needs(basmatiRice, 200).needs(chicken, 200).needs(onion, 40).special());
        dishes.add(new Dish("Adana Kebab", Dish.CAT_INTERNATIONAL, 420.00, "beef_steak.png")
                .needs(beefCut, 200).needs(onion, 30).needs(blackPepper, 5).special());
        dishes.add(new Dish("Beef Nihari", Dish.CAT_INTERNATIONAL, 400.00, "beef_steak.png")
                .needs(beefCut, 250).needs(onion, 40).needs(ghee, 20).special());
        dishes.add(new Dish("Chicken Ramen", Dish.CAT_INTERNATIONAL, 350.00, "sushi_platter.png")
                .needs(ramenNoodles, 150).needs(chicken, 120).needs(soySauce, 30).needs(egg, 1).special());
        dishes.add(new Dish("Butter Chicken", Dish.CAT_INTERNATIONAL, 350.00, "thai_curry.png")
                .needs(chicken, 200).needs(butter, 30).needs(cream, 60).needs(tomato, 80).special());
        dishes.add(new Dish("Kunafa", Dish.CAT_INTERNATIONAL, 250.00, "gulab_jamun.png")
                .needs(kunafaDough, 120).needs(cheese, 60).needs(sugarSyrup, 40).needs(ghee, 20).special());

        // ---- SALAD ------------------------------------------------------------------------
        dishes.add(new Dish("Chicken Salad", Dish.CAT_SALAD, 220.00, "salad.png"));
        dishes.add(new Dish("Russian Salad", Dish.CAT_SALAD, 180.00, "salad.png"));
        dishes.add(new Dish("Corn Salad", Dish.CAT_SALAD, 150.00, "salad.png"));
        dishes.add(new Dish("Special House Salad", Dish.CAT_SALAD, 250.00, "salad.png"));

        // ---- COLD DRINKS --------------------------------------------------------------------
        dishes.add(new Dish("Mineral Water", Dish.CAT_COLD_DRINKS, 20.00, "juice.png"));
        dishes.add(new Dish("Fresh Lemonade", Dish.CAT_COLD_DRINKS, 60.00, "juice.png"));
        dishes.add(new Dish("Mango Juice", Dish.CAT_COLD_DRINKS, 80.00, "juice.png"));
        dishes.add(new Dish("Watermelon Juice", Dish.CAT_COLD_DRINKS, 80.00, "juice.png"));

        // ---- MILKSHAKES & SPECIAL DRINKS -----------------------------------------------------
        dishes.add(new Dish("Vanilla Milkshake", Dish.CAT_MILKSHAKES, 180.00, "mango_shake.png"));
        dishes.add(new Dish("Chocolate Milkshake", Dish.CAT_MILKSHAKES, 200.00, "mango_shake.png"));
        dishes.add(new Dish("Oreo Shake", Dish.CAT_MILKSHAKES, 220.00, "mango_shake.png"));
        dishes.add(new Dish("Cold Coffee", Dish.CAT_MILKSHAKES, 160.00, "mango_shake.png"));

        // ---- HOT BEVERAGES ---------------------------------------------------------------------
        dishes.add(new Dish("Tea", Dish.CAT_HOT_BEVERAGES, 30.00, "juice.png"));
        dishes.add(new Dish("Masala Tea", Dish.CAT_HOT_BEVERAGES, 40.00, "juice.png"));
        dishes.add(new Dish("Espresso", Dish.CAT_HOT_BEVERAGES, 100.00, "juice.png"));
        dishes.add(new Dish("Cappuccino", Dish.CAT_HOT_BEVERAGES, 120.00, "juice.png"));

        // Staff is NOT seeded here - see loadStaffFromDatabase()/seedStaffIfEmpty():
        // the "staff" table in SQLite is the single source of truth for staff members,
        // so the same 3 people are not duplicated every time the app restarts.
    }

    /** DATABASE INTEGRATION: called once at startup - loads staff from SQLite, seeding it the first time. */
    private void seedStaffIfEmpty() {
        List<Object[]> rows = DatabaseManager.readAllStaff();
        if (!rows.isEmpty()) {
            for (Object[] row : rows) {
                addStaffFromDbRow(row);
            }
            return;
        }
        // first run ever (or database unavailable) -> create 3 default staff members
        addStaff(new Person("Rahim Uddin", "Male", "Expert", "Bangladesh",
                LocalDate.of(1985, 4, 12), "Reading, Traveling", "-"));
        addStaff(new Person("Ayesha Rahman", "Female", "Intermediate", "Bangladesh",
                LocalDate.of(1994, 9, 3), "Gaming", "-"));
        addStaff(new Person("John Smith", "Male", "Beginner", "United Kingdom",
                LocalDate.of(2001, 1, 20), "Traveling", "-"));
    }

    private void addStaffFromDbRow(Object[] row) {
        int id = (int) row[0];
        String name = (String) row[1];
        LocalDate dob = null;
        try {
            dob = LocalDate.parse((String) row[5]);
        } catch (Exception ignored) {
            // older / malformed row - leave date of birth blank rather than crash
        }
        Person person = new Person(name, (String) row[2], (String) row[3], (String) row[4],
                dob, (String) row[6], (String) row[7]);
        person.setId(id);
        staff.add(person);
    }

    // ------------------------------------------------------------------ STAFF CRUD (Create/Read/Update/Delete)

    /** CREATE: adds a staff member to the in-memory table AND persists it in SQLite. */
    public void addStaff(Person person) {
        staff.add(person);
        int id = DatabaseManager.insertStaff(person.getName(), person.getGender(), person.getSkillLevel(),
                person.getCountry(), person.getDateOfBirth() == null ? "" : person.getDateOfBirth().toString(),
                person.getHobbies(), person.getPhotoFile());
        person.setId(id);
    }

    /** UPDATE: changes a staff member's skill level, in memory and in the database. */
    public void updateStaffSkill(Person person, String newSkillLevel) {
        person.setSkillLevel(newSkillLevel);
        DatabaseManager.updateStaffSkill(person.getId(), newSkillLevel);
    }

    /** DELETE: removes a staff member from the table AND from the database. */
    public void removeStaff(Person person) {
        staff.remove(person);
        DatabaseManager.deleteStaff(person.getId());
    }

    // ------------------------------------------------------------------ INGREDIENT CRUD extras

    /** UPDATE: adds stock to an ingredient and persists the new quantity (used by "Restock"). */
    public void restockIngredient(Ingredient ingredient, double amount) {
        ingredient.add(amount);
        DatabaseManager.upsertIngredient(ingredient.getName(), ingredient.getUnit(),
                ingredient.getQuantity(), ingredient.getMinLevel());
    }

    /** DELETE: removes an ingredient completely (from every dish's recipe, the table, and the database). */
    public void deleteIngredient(Ingredient ingredient) {
        ingredients.remove(ingredient);
        DatabaseManager.deleteIngredient(ingredient.getName());
    }

    // ------------------------------------------------------------------ CONCURRENCY (Task + thread pool)

    /**
     * Simulates asking an outside supplier for today's unit price of an ingredient.
     * Deliberately slow (network-like latency) to show why this MUST run off the UI thread:
     * the returned {@link Task} is meant to be handed to {@link #getExecutor()}.
     */
    public Task<Double> checkSupplierPriceTask(Ingredient ingredient) {
        return new Task<>() {
            @Override
            protected Double call() throws Exception {
                updateMessage("Contacting supplier for " + ingredient.getName() + "...");
                Thread.sleep(1200); // simulated network / supplier lookup latency
                double basePrice = 5 + (ingredient.getName().length() * 1.75);
                double swing = (Math.random() * 0.3) - 0.15; // -15% .. +15%
                return Math.round(basePrice * (1 + swing) * 100.0) / 100.0;
            }
        };
    }

    /**
     * Fetches today's live USD -> BDT exchange rate over the internet (NETWORKING & DATA PARSING).
     * Must be run on a background thread (see {@link #getExecutor()}) - it blocks on the HTTP call.
     */
    public Task<Double> fetchExchangeRateTask() {
        return new Task<>() {
            @Override
            protected Double call() throws Exception {
                updateMessage("Fetching live USD -> BDT rate...");
                return NetworkUtil.fetchUsdToBdtRate();
            }
        };
    }

    // ------------------------------------------------------------------ REPORTS (Reportable interface, polymorphism)

    /**
     * ADVANCED OOP - POLYMORPHISM: Dish, Ingredient and Person are unrelated classes but all
     * implement {@link Reportable}, so they can be summarised here through one common interface
     * type without this method knowing (or caring) which concrete class each item really is.
     */
    public List<String> generateReportSummaries() {
        List<Reportable> everything = new ArrayList<>();
        everything.addAll(dishes);
        everything.addAll(ingredients);
        everything.addAll(staff);
        return everything.stream().map(Reportable::getSummary).collect(Collectors.toList());
    }
}
