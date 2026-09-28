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



    /**
     * Sample data so the app is useful the first time you run it.
     *
     * This is the restaurant's full published menu (15 sections, 190 dishes). Every dish now
     * has a real recipe (see the .needs(...) chain after each Dish(...) below), so ordering
     * ANY dish deducts the right ingredients from stock and can show "out of stock" /
     * "Ingredients needed" correctly. The 12 dishes marked as the restaurant's own "Chef's
     * Special" board also carry the .special() flag (star badge on the menu).
     */
    private void seedData() {
        // ---- ingredients (only what the recipe-tracked dishes below actually need) -----
        Ingredient basmatiRice   = stock("Basmati Rice",     "g",   3000, 500);
        Ingredient muttonMeat    = stock("Mutton",           "g",   1500, 300);
        Ingredient yogurt        = stock("Yogurt",           "ml",  1000, 200);
        Ingredient onion         = stock("Onion",            "g",   1500, 300);
        Ingredient beefCut       = stock("Beef Steak Cut",   "g",   1500, 300);
        Ingredient butter        = stock("Butter",           "g",   500,  100);
        Ingredient blackPepper   = stock("Black Pepper",     "g",   150,  30);
        Ingredient patty         = stock("Beef Patty",       "pcs", 6,    2);
        Ingredient bun           = stock("Burger Bun",       "pcs", 10,   2);
        Ingredient cheese        = stock("Cheese Slice",     "pcs", 12,   3);
        Ingredient dough         = stock("Pizza Dough",      "pcs", 8,    2);
        Ingredient mozzarella    = stock("Mozzarella",       "g",   1200, 300);
        Ingredient sauce         = stock("Tomato Sauce",     "ml",  1000, 200);
        Ingredient chicken       = stock("Chicken",          "g",   1500, 300);
        Ingredient coconutMilk   = stock("Coconut Milk",     "ml",  1000, 200);
        Ingredient curryPaste    = stock("Green Curry Paste","g",   300,  60);
        Ingredient ghee          = stock("Ghee",             "g",   300,  60);
        Ingredient kunafaDough   = stock("Kunafa Dough",     "g",   500,  100);
        Ingredient sugarSyrup    = stock("Sugar Syrup",      "ml",  500,  100);
        Ingredient tomato        = stock("Tomato",           "g",   1500, 300);
        Ingredient cream         = stock("Cream",            "ml",  1200, 250);
        Ingredient ramenNoodles  = stock("Ramen Noodles",    "g",   1200, 250);
        Ingredient soySauce      = stock("Soy Sauce",        "ml",  600,  120);
        Ingredient egg           = stock("Egg",              "pcs", 24,   6);
        Ingredient spaghetti     = stock("Spaghetti",        "g",   2000, 400);
        Ingredient lasagnaSheets = stock("Lasagna Sheets",   "pcs", 40,   10);

        // ---- extra ingredients so every one of the 190 menu dishes has a real recipe ----
        Ingredient garlic            = stock("Garlic", "g", 500, 100);
        Ingredient ginger            = stock("Ginger", "g", 400, 80);
        Ingredient cookingOil        = stock("Cooking Oil", "ml", 3000, 500);
        Ingredient mixedVeg          = stock("Mixed Vegetables", "g", 2000, 400);
        Ingredient capsicum          = stock("Capsicum", "g", 1000, 200);
        Ingredient bbqSauce          = stock("BBQ Sauce", "ml", 800, 150);
        Ingredient breadCrumbs       = stock("Bread Crumbs", "g", 1000, 200);
        Ingredient chickenPatty      = stock("Chicken Patty", "pcs", 8, 2);
        Ingredient lettuce           = stock("Lettuce", "g", 1000, 200);
        Ingredient mushroom          = stock("Mushroom", "g", 1200, 250);
        Ingredient pepperoni         = stock("Pepperoni", "g", 600, 120);
        Ingredient shrimp            = stock("Shrimp", "g", 800, 150);
        Ingredient cheddar           = stock("Cheddar Cheese", "g", 800, 150);
        Ingredient noodles           = stock("Egg Noodles", "g", 2000, 400);
        Ingredient potato            = stock("Potato", "g", 3000, 500);
        Ingredient breadLoaf         = stock("Bread Loaf", "pcs", 15, 3);
        Ingredient springRollWrapper = stock("Spring Roll Wrapper", "pcs", 40, 10);
        Ingredient samosaPastry      = stock("Samosa Pastry", "pcs", 40, 10);
        Ingredient tortillaChips     = stock("Tortilla Chips", "g", 1000, 200);
        Ingredient chickenStock      = stock("Chicken Stock", "ml", 3000, 500);
        Ingredient sweetCorn         = stock("Sweet Corn", "g", 1500, 300);
        Ingredient vinegar           = stock("Vinegar", "ml", 500, 100);
        Ingredient chilliSauce       = stock("Chilli Sauce", "ml", 500, 100);
        Ingredient tortilla          = stock("Tortilla Wrap", "pcs", 30, 6);
        Ingredient subRoll           = stock("Sub Roll", "pcs", 15, 3);
        Ingredient tacoShell         = stock("Taco Shell", "pcs", 30, 6);
        Ingredient lambMeat          = stock("Lamb Meat", "g", 1000, 200);
        Ingredient chickpeas         = stock("Chickpeas", "g", 1500, 300);
        Ingredient tahini            = stock("Tahini", "ml", 400, 80);
        Ingredient lentils           = stock("Lentils (Dal)", "g", 1500, 300);
        Ingredient spinach           = stock("Spinach", "g", 1200, 250);
        Ingredient paneer            = stock("Paneer", "g", 1000, 200);
        Ingredient naanDough         = stock("Naan Dough", "pcs", 30, 6);
        Ingredient milk              = stock("Milk", "ml", 4000, 800);
        Ingredient sushiRice         = stock("Sushi Rice", "g", 1500, 300);
        Ingredient nori              = stock("Nori Sheets", "pcs", 30, 6);
        Ingredient teriyakiSauce     = stock("Teriyaki Sauce", "ml", 600, 120);
        Ingredient mascarpone        = stock("Mascarpone Cheese", "g", 800, 150);
        Ingredient coffeePowder      = stock("Coffee Powder", "g", 1000, 200);
        Ingredient cocoaPowder       = stock("Cocoa Powder", "g", 1000, 200);
        Ingredient cucumber          = stock("Cucumber", "g", 1200, 250);
        Ingredient mayonnaise        = stock("Mayonnaise", "ml", 1000, 200);
        Ingredient mixedFruit        = stock("Mixed Fruit", "g", 2000, 400);
        Ingredient flour             = stock("Flour", "g", 3000, 500);
        Ingredient sugar             = stock("Sugar", "g", 3000, 500);
        Ingredient creamCheese       = stock("Cream Cheese", "g", 1000, 200);
        Ingredient vanillaIceCream   = stock("Vanilla Ice Cream", "g", 3000, 500);
        Ingredient faloodaMix        = stock("Falooda Mix", "g", 1000, 200);
        Ingredient caramelSyrup      = stock("Caramel Syrup", "ml", 600, 120);
        Ingredient colaSyrup         = stock("Cola Syrup", "ml", 1000, 200);
        Ingredient bottledWater      = stock("Bottled Water", "pcs", 50, 10);
        Ingredient lemon             = stock("Lemon", "pcs", 60, 10);
        Ingredient mintLeaves        = stock("Mint Leaves", "g", 300, 60);
        Ingredient orange            = stock("Orange", "pcs", 60, 10);
        Ingredient mangoPulp         = stock("Mango Pulp", "ml", 2000, 400);
        Ingredient watermelon        = stock("Watermelon", "g", 3000, 500);
        Ingredient pineapple         = stock("Pineapple", "g", 2000, 400);
        Ingredient apple             = stock("Apple", "pcs", 60, 10);
        Ingredient chocolateSyrup    = stock("Chocolate Syrup", "ml", 1500, 300);
        Ingredient strawberrySyrup   = stock("Strawberry Syrup", "ml", 1000, 200);
        Ingredient oreoCookies       = stock("Oreo Cookies", "pcs", 100, 20);
        Ingredient kitkatBar         = stock("KitKat Bar", "pcs", 60, 12);
        Ingredient browniePiece      = stock("Brownie Piece", "pcs", 40, 8);
        Ingredient teaLeaves         = stock("Tea Leaves", "g", 1000, 200);
        Ingredient teaMasala         = stock("Tea Masala Mix", "g", 500, 100);

        // ================================================================================
        // 1. BIRYANI & RICE
        // ================================================================================
        dishes.add(new Dish("Chicken Biryani", Dish.CAT_BIRYANI, 250.00, "chicken_biryani.png")
                .needs(basmatiRice, 180).needs(chicken, 180).needs(yogurt, 40).needs(onion, 25));
        dishes.add(new Dish("Beef Biryani", Dish.CAT_BIRYANI, 280.00, "beef_biryani.png")
                .needs(basmatiRice, 180).needs(beefCut, 200).needs(yogurt, 40).needs(onion, 25));
        dishes.add(new Dish("Kacchi Biryani", Dish.CAT_BIRYANI, 320.00, "kacchi_biryani.png")
                .needs(basmatiRice, 200).needs(muttonMeat, 200).needs(yogurt, 50).needs(onion, 30).special());
        dishes.add(new Dish("Mutton Kacchi", Dish.CAT_BIRYANI, 380.00, "mutton_kacchi.png")
                .needs(basmatiRice, 200).needs(muttonMeat, 220).needs(yogurt, 60).needs(onion, 30).needs(ghee, 15));
        dishes.add(new Dish("Chicken Tehari", Dish.CAT_BIRYANI, 220.00, "chicken_tehari.png")
                .needs(basmatiRice, 180).needs(chicken, 150).needs(onion, 25).needs(cookingOil, 15));
        dishes.add(new Dish("Beef Tehari", Dish.CAT_BIRYANI, 260.00, "beef_tehari.png")
                .needs(basmatiRice, 180).needs(beefCut, 180).needs(onion, 25).needs(cookingOil, 15));
        dishes.add(new Dish("Chicken Fried Rice", Dish.CAT_BIRYANI, 220.00, "chicken_fried_rice.png")
                .needs(basmatiRice, 180).needs(chicken, 100).needs(egg, 1).needs(soySauce, 15).needs(mixedVeg, 50));
        dishes.add(new Dish("Egg Fried Rice", Dish.CAT_BIRYANI, 180.00, "egg_fried_rice.png")
                .needs(basmatiRice, 180).needs(egg, 2).needs(soySauce, 15).needs(mixedVeg, 50));
        dishes.add(new Dish("Plain Rice", Dish.CAT_BIRYANI, 80.00, "plain_rice.png")
                .needs(basmatiRice, 150));
        dishes.add(new Dish("Special Fried Rice", Dish.CAT_BIRYANI, 320.00, "special_fried_rice.png")
                .needs(basmatiRice, 180).needs(chicken, 80).needs(beefCut, 60).needs(egg, 1).needs(soySauce, 20).needs(mixedVeg, 60));

        // ================================================================================
        // 2. CHICKEN SPECIALS
        // ================================================================================
        dishes.add(new Dish("Grilled Chicken", Dish.CAT_CHICKEN, 320.00, "grilled_chicken.png")
                .needs(chicken, 220).needs(garlic, 10).needs(blackPepper, 3).needs(cookingOil, 15));
        dishes.add(new Dish("BBQ Chicken", Dish.CAT_CHICKEN, 350.00, "bbq_chicken.png")
                .needs(chicken, 220).needs(bbqSauce, 40).needs(garlic, 10));
        dishes.add(new Dish("Chicken Shashlik", Dish.CAT_CHICKEN, 280.00, "chicken_shashlik.png")
                .needs(chicken, 200).needs(capsicum, 60).needs(onion, 40).needs(cookingOil, 15));
        dishes.add(new Dish("Chicken Tandoori", Dish.CAT_CHICKEN, 300.00, "chicken_tandoori.png")
                .needs(chicken, 220).needs(yogurt, 60).needs(garlic, 10).needs(blackPepper, 3));
        dishes.add(new Dish("Chicken Roast", Dish.CAT_CHICKEN, 280.00, "chicken_roast.png")
                .needs(chicken, 220).needs(onion, 30).needs(ghee, 20).needs(garlic, 10));
        dishes.add(new Dish("Chicken Curry", Dish.CAT_CHICKEN, 240.00, "chicken_curry.png")
                .needs(chicken, 200).needs(onion, 40).needs(garlic, 10).needs(ginger, 8));
        dishes.add(new Dish("Crispy Fried Chicken", Dish.CAT_CHICKEN, 220.00, "crispy_fried_chicken.png")
                .needs(chicken, 200).needs(breadCrumbs, 60).needs(egg, 1).needs(cookingOil, 30));
        dishes.add(new Dish("Chicken Steak", Dish.CAT_CHICKEN, 380.00, "chicken_steak.png")
                .needs(chicken, 250).needs(butter, 15).needs(blackPepper, 5));
        dishes.add(new Dish("Sweet & Sour Chicken", Dish.CAT_CHICKEN, 300.00, "sweet_sour_chicken.png")
                .needs(chicken, 200).needs(capsicum, 50).needs(sauce, 40).needs(sugar, 20));

        // ================================================================================
        // 3. BEEF & MUTTON
        // ================================================================================
        dishes.add(new Dish("Beef Steak", Dish.CAT_BEEF_MUTTON, 450.00, "beef_steak.png")
                .needs(beefCut, 250).needs(butter, 20).needs(blackPepper, 5).special());
        dishes.add(new Dish("Beef Kala Bhuna", Dish.CAT_BEEF_MUTTON, 350.00, "beef_kala_bhuna.png")
                .needs(beefCut, 220).needs(onion, 40).needs(garlic, 12).needs(ginger, 10).needs(cookingOil, 20));
        dishes.add(new Dish("Beef Curry", Dish.CAT_BEEF_MUTTON, 280.00, "beef_curry.png")
                .needs(beefCut, 220).needs(onion, 40).needs(garlic, 10).needs(ginger, 8));
        dishes.add(new Dish("Beef Masala", Dish.CAT_BEEF_MUTTON, 300.00, "beef_masala.png")
                .needs(beefCut, 220).needs(onion, 40).needs(tomato, 40).needs(garlic, 10));
        dishes.add(new Dish("Beef Shashlik", Dish.CAT_BEEF_MUTTON, 350.00, "beef_shashlik.png")
                .needs(beefCut, 200).needs(capsicum, 60).needs(onion, 40));
        dishes.add(new Dish("Mutton Curry", Dish.CAT_BEEF_MUTTON, 350.00, "mutton_curry.png")
                .needs(muttonMeat, 220).needs(onion, 40).needs(garlic, 10).needs(ginger, 10));
        dishes.add(new Dish("Mutton Bhuna", Dish.CAT_BEEF_MUTTON, 380.00, "mutton_bhuna.png")
                .needs(muttonMeat, 250).needs(onion, 40).needs(garlic, 12).needs(ginger, 10).needs(ghee, 15));
        dishes.add(new Dish("Mutton Rezala", Dish.CAT_BEEF_MUTTON, 360.00, "mutton_rezala.png")
                .needs(muttonMeat, 230).needs(yogurt, 60).needs(onion, 30).needs(ghee, 20));
        dishes.add(new Dish("Mutton Korma", Dish.CAT_BEEF_MUTTON, 380.00, "mutton_korma.png")
                .needs(muttonMeat, 250).needs(yogurt, 50).needs(cream, 40).needs(ghee, 20));

        // ================================================================================
        // 4. BURGERS
        // ================================================================================
        dishes.add(new Dish("Crispy Chicken Burger", Dish.CAT_BURGERS, 250.00, "crispy_chicken_burger.png")
                .needs(chickenPatty, 1).needs(bun, 1).needs(lettuce, 20).needs(mayonnaise, 15));
        dishes.add(new Dish("BBQ Chicken Burger", Dish.CAT_BURGERS, 280.00, "bbq_chicken_burger.png")
                .needs(chickenPatty, 1).needs(bun, 1).needs(bbqSauce, 20).needs(onion, 15));
        dishes.add(new Dish("Spicy Chicken Burger", Dish.CAT_BURGERS, 270.00, "spicy_chicken_burger.png")
                .needs(chickenPatty, 1).needs(bun, 1).needs(chilliSauce, 15).needs(lettuce, 15));
        dishes.add(new Dish("Chicken Cheese Burger", Dish.CAT_BURGERS, 300.00, "chicken_cheese_burger.png")
                .needs(chickenPatty, 1).needs(bun, 1).needs(cheese, 1).needs(lettuce, 15));
        dishes.add(new Dish("Beef Burger", Dish.CAT_BURGERS, 280.00, "beef_burger.png")
                .needs(patty, 1).needs(bun, 1).needs(lettuce, 15).needs(onion, 15));
        dishes.add(new Dish("Beef Cheese Burger", Dish.CAT_BURGERS, 320.00, "beef_cheese_burger.png")
                .needs(patty, 1).needs(bun, 1).needs(cheese, 1).needs(lettuce, 15));
        dishes.add(new Dish("Double Beef Burger", Dish.CAT_BURGERS, 380.00, "double_beef_burger.png")
                .needs(patty, 2).needs(bun, 1).needs(cheese, 1).needs(onion, 20));
        dishes.add(new Dish("Double Chicken Burger", Dish.CAT_BURGERS, 350.00, "double_chicken_burger.png")
                .needs(chickenPatty, 2).needs(bun, 1).needs(cheese, 1).needs(lettuce, 20));
        dishes.add(new Dish("Mushroom Burger", Dish.CAT_BURGERS, 300.00, "mushroom_burger.png")
                .needs(patty, 1).needs(bun, 1).needs(mushroom, 40).needs(cheese, 1));
        dishes.add(new Dish("BBQ Beef Burger", Dish.CAT_BURGERS, 350.00, "bbq_beef_burger.png")
                .needs(patty, 1).needs(bun, 1).needs(bbqSauce, 25).needs(onion, 20));
        dishes.add(new Dish("Special House Burger", Dish.CAT_BURGERS, 420.00, "special_house_burger.png")
                .needs(patty, 2).needs(bun, 1).needs(cheese, 2).needs(onion, 20).special());

        // ================================================================================
        // 5. PIZZA
        // ================================================================================
        dishes.add(new Dish("Margherita Pizza", Dish.CAT_PIZZA, 350.00, "margherita_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80));
        dishes.add(new Dish("Chicken Pizza", Dish.CAT_PIZZA, 420.00, "chicken_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80).needs(chicken, 100));
        dishes.add(new Dish("BBQ Chicken Pizza", Dish.CAT_PIZZA, 480.00, "bbq_chicken_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(bbqSauce, 60).needs(chicken, 100));
        dishes.add(new Dish("Beef Pizza", Dish.CAT_PIZZA, 450.00, "beef_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80).needs(beefCut, 100));
        dishes.add(new Dish("Pepperoni Pizza", Dish.CAT_PIZZA, 500.00, "pepperoni_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80).needs(pepperoni, 90));
        dishes.add(new Dish("Cheese Lovers Pizza", Dish.CAT_PIZZA, 450.00, "cheese_lovers_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(cheddar, 100).needs(cheese, 2));
        dishes.add(new Dish("Mushroom Pizza", Dish.CAT_PIZZA, 400.00, "mushroom_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 70).needs(mushroom, 80));
        dishes.add(new Dish("Mexican Pizza", Dish.CAT_PIZZA, 480.00, "mexican_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80).needs(capsicum, 50).needs(beefCut, 80));
        dishes.add(new Dish("Spicy Chicken Pizza", Dish.CAT_PIZZA, 450.00, "spicy_chicken_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(chilliSauce, 50).needs(chicken, 100));
        dishes.add(new Dish("Seafood Pizza", Dish.CAT_PIZZA, 550.00, "seafood_pizza.png")
                .needs(dough, 1).needs(mozzarella, 150).needs(sauce, 80).needs(shrimp, 100));
        dishes.add(new Dish("Four Cheese Pizza", Dish.CAT_PIZZA, 520.00, "four_cheese_pizza.png")
                .needs(dough, 1).needs(mozzarella, 120).needs(cheddar, 100).needs(cheese, 2).needs(cream, 30));
        dishes.add(new Dish("Special House Pizza", Dish.CAT_PIZZA, 600.00, "special_house_pizza.png")
                .needs(dough, 1).needs(mozzarella, 200).needs(sauce, 100).needs(chicken, 100).needs(beefCut, 100).special());

        // ================================================================================
        // 6. CHINESE
        // ================================================================================
        dishes.add(new Dish("Chicken Chow Mein", Dish.CAT_CHINESE, 220.00, "chicken_chow_mein.png")
                .needs(noodles, 200).needs(chicken, 120).needs(soySauce, 20).needs(mixedVeg, 60));
        dishes.add(new Dish("Beef Chow Mein", Dish.CAT_CHINESE, 260.00, "beef_chow_mein.png")
                .needs(noodles, 200).needs(beefCut, 130).needs(soySauce, 20).needs(mixedVeg, 60));
        dishes.add(new Dish("Mixed Chow Mein", Dish.CAT_CHINESE, 300.00, "mixed_chow_mein.png")
                .needs(noodles, 220).needs(chicken, 70).needs(beefCut, 70).needs(soySauce, 25).needs(mixedVeg, 70));
        dishes.add(new Dish("Vegetable Chow Mein", Dish.CAT_CHINESE, 180.00, "vegetable_chow_mein.png")
                .needs(noodles, 200).needs(mixedVeg, 100).needs(soySauce, 20));
        dishes.add(new Dish("Chicken Pasta", Dish.CAT_CHINESE, 250.00, "chicken_pasta.png")
                .needs(spaghetti, 180).needs(chicken, 130).needs(sauce, 70).needs(garlic, 10));
        dishes.add(new Dish("Beef Pasta", Dish.CAT_CHINESE, 280.00, "beef_pasta.png")
                .needs(spaghetti, 180).needs(beefCut, 140).needs(sauce, 70).needs(garlic, 10));
        dishes.add(new Dish("Creamy Chicken Pasta", Dish.CAT_CHINESE, 300.00, "creamy_chicken_pasta.png")
                .needs(spaghetti, 180).needs(chicken, 130).needs(cream, 80).needs(garlic, 10));
        dishes.add(new Dish("Chicken Chilli", Dish.CAT_CHINESE, 280.00, "chicken_chilli.png")
                .needs(chicken, 200).needs(capsicum, 50).needs(chilliSauce, 30).needs(soySauce, 15));
        dishes.add(new Dish("Beef Chilli", Dish.CAT_CHINESE, 320.00, "beef_chilli.png")
                .needs(beefCut, 200).needs(capsicum, 50).needs(chilliSauce, 30).needs(soySauce, 15));
        dishes.add(new Dish("Chicken Szechuan", Dish.CAT_CHINESE, 300.00, "chicken_szechuan.png")
                .needs(chicken, 200).needs(capsicum, 50).needs(chilliSauce, 40).needs(garlic, 12));
        dishes.add(new Dish("Chinese Mixed Platter", Dish.CAT_CHINESE, 450.00, "chinese_mixed_platter.png")
                .needs(chicken, 100).needs(beefCut, 100).needs(shrimp, 80).needs(mixedVeg, 80).needs(soySauce, 20));

        // ================================================================================
        // 7. APPETIZERS & SNACKS
        // ================================================================================
        dishes.add(new Dish("French Fries", Dish.CAT_APPETIZERS, 70.00, "french_fries.png")
                .needs(potato, 250).needs(cookingOil, 30));
        dishes.add(new Dish("Cheese Fries", Dish.CAT_APPETIZERS, 150.00, "cheese_fries.png")
                .needs(potato, 250).needs(cheese, 2).needs(cookingOil, 30));
        dishes.add(new Dish("Chicken Nuggets", Dish.CAT_APPETIZERS, 180.00, "chicken_nuggets.png")
                .needs(chicken, 150).needs(breadCrumbs, 50).needs(egg, 1).needs(cookingOil, 30));
        dishes.add(new Dish("Chicken Wings", Dish.CAT_APPETIZERS, 120.00, "chicken_wings.png")
                .needs(chicken, 250).needs(bbqSauce, 30).needs(cookingOil, 20));
        dishes.add(new Dish("BBQ Wings", Dish.CAT_APPETIZERS, 180.00, "bbq_wings.png")
                .needs(chicken, 250).needs(bbqSauce, 50));
        dishes.add(new Dish("Chicken Popcorn", Dish.CAT_APPETIZERS, 190.00, "chicken_popcorn.png")
                .needs(chicken, 150).needs(breadCrumbs, 50).needs(cookingOil, 30));
        dishes.add(new Dish("Garlic Bread", Dish.CAT_APPETIZERS, 110.00, "garlic_bread.png")
                .needs(breadLoaf, 1).needs(garlic, 15).needs(butter, 20));
        dishes.add(new Dish("Cheese Garlic Bread", Dish.CAT_APPETIZERS, 150.00, "cheese_garlic_bread.png")
                .needs(breadLoaf, 1).needs(garlic, 15).needs(butter, 20).needs(cheese, 2));
        dishes.add(new Dish("Spring Roll", Dish.CAT_APPETIZERS, 120.00, "spring_roll.png")
                .needs(springRollWrapper, 4).needs(mixedVeg, 80).needs(cookingOil, 20));
        dishes.add(new Dish("Chicken Spring Roll", Dish.CAT_APPETIZERS, 150.00, "chicken_spring_roll.png")
                .needs(springRollWrapper, 4).needs(chicken, 60).needs(mixedVeg, 50).needs(cookingOil, 20));
        dishes.add(new Dish("Chicken Samosa", Dish.CAT_APPETIZERS, 100.00, "chicken_samosa.png")
                .needs(samosaPastry, 3).needs(chicken, 60).needs(onion, 20).needs(cookingOil, 20));
        dishes.add(new Dish("Nachos", Dish.CAT_APPETIZERS, 250.00, "nachos.png")
                .needs(tortillaChips, 150).needs(cheese, 2).needs(tomato, 40).needs(chilliSauce, 20));
        dishes.add(new Dish("Chicken Finger", Dish.CAT_APPETIZERS, 220.00, "chicken_finger.png")
                .needs(chicken, 180).needs(breadCrumbs, 60).needs(egg, 1).needs(cookingOil, 30));
        dishes.add(new Dish("Special Snack Platter", Dish.CAT_APPETIZERS, 450.00, "special_snack_platter.png")
                .needs(chicken, 100).needs(potato, 100).needs(mixedVeg, 60).needs(cheese, 2).needs(cookingOil, 30));

        // ================================================================================
        // 8. SOUP
        // ================================================================================
        dishes.add(new Dish("Chicken Corn Soup", Dish.CAT_SOUP, 180.00, "chicken_corn_soup.png")
                .needs(chickenStock, 300).needs(chicken, 60).needs(sweetCorn, 80).needs(egg, 1));
        dishes.add(new Dish("Hot & Sour Soup", Dish.CAT_SOUP, 200.00, "hot_sour_soup.png")
                .needs(chickenStock, 300).needs(vinegar, 20).needs(chilliSauce, 20).needs(egg, 1));
        dishes.add(new Dish("Thai Soup", Dish.CAT_SOUP, 220.00, "thai_soup.png")
                .needs(chickenStock, 250).needs(coconutMilk, 100).needs(curryPaste, 20));
        dishes.add(new Dish("Seafood Soup", Dish.CAT_SOUP, 280.00, "seafood_soup.png")
                .needs(chickenStock, 300).needs(shrimp, 80).needs(mixedVeg, 40));
        dishes.add(new Dish("Mushroom Soup", Dish.CAT_SOUP, 180.00, "mushroom_soup.png")
                .needs(chickenStock, 250).needs(mushroom, 100).needs(cream, 60));
        dishes.add(new Dish("Cream of Chicken Soup", Dish.CAT_SOUP, 220.00, "cream_of_chicken_soup.png")
                .needs(chickenStock, 250).needs(chicken, 70).needs(cream, 60));
        dishes.add(new Dish("Vegetable Soup", Dish.CAT_SOUP, 150.00, "vegetable_soup.png")
                .needs(chickenStock, 250).needs(mixedVeg, 100));
        dishes.add(new Dish("Chicken Mushroom Soup", Dish.CAT_SOUP, 220.00, "chicken_mushroom_soup.png")
                .needs(chickenStock, 250).needs(chicken, 60).needs(mushroom, 80));
        dishes.add(new Dish("Special Mixed Soup", Dish.CAT_SOUP, 300.00, "special_mixed_soup.png")
                .needs(chickenStock, 300).needs(chicken, 50).needs(shrimp, 50).needs(mixedVeg, 60));
        dishes.add(new Dish("Sweet Corn Soup", Dish.CAT_SOUP, 160.00, "sweet_corn_soup.png")
                .needs(chickenStock, 250).needs(sweetCorn, 100).needs(egg, 1));

        // ================================================================================
        // 9. MEXICAN & FAST FOOD
        // ================================================================================
        dishes.add(new Dish("Chicken Wrap", Dish.CAT_MEXICAN, 220.00, "chicken_wrap.png")
                .needs(tortilla, 1).needs(chicken, 120).needs(lettuce, 30).needs(mayonnaise, 15));
        dishes.add(new Dish("Beef Wrap", Dish.CAT_MEXICAN, 260.00, "beef_wrap.png")
                .needs(tortilla, 1).needs(beefCut, 130).needs(lettuce, 30).needs(mayonnaise, 15));
        dishes.add(new Dish("Chicken Shawarma", Dish.CAT_MEXICAN, 180.00, "chicken_shawarma.png")
                .needs(tortilla, 1).needs(chicken, 130).needs(garlic, 8).needs(yogurt, 20));
        dishes.add(new Dish("Beef Shawarma", Dish.CAT_MEXICAN, 220.00, "beef_shawarma.png")
                .needs(tortilla, 1).needs(beefCut, 140).needs(garlic, 8).needs(yogurt, 20));
        dishes.add(new Dish("Chicken Quesadilla", Dish.CAT_MEXICAN, 280.00, "chicken_quesadilla.png")
                .needs(tortilla, 2).needs(chicken, 100).needs(cheese, 2).needs(capsicum, 30));
        dishes.add(new Dish("Beef Quesadilla", Dish.CAT_MEXICAN, 320.00, "beef_quesadilla.png")
                .needs(tortilla, 2).needs(beefCut, 110).needs(cheese, 2).needs(capsicum, 30));
        dishes.add(new Dish("Chicken Taco", Dish.CAT_MEXICAN, 200.00, "chicken_taco.png")
                .needs(tacoShell, 2).needs(chicken, 90).needs(lettuce, 20).needs(tomato, 20));
        dishes.add(new Dish("Beef Taco", Dish.CAT_MEXICAN, 230.00, "beef_taco.png")
                .needs(tacoShell, 2).needs(beefCut, 100).needs(lettuce, 20).needs(tomato, 20));
        dishes.add(new Dish("Chicken Burrito", Dish.CAT_MEXICAN, 280.00, "chicken_burrito.png")
                .needs(tortilla, 1).needs(chicken, 130).needs(basmatiRice, 60).needs(cheese, 1));
        dishes.add(new Dish("Beef Burrito", Dish.CAT_MEXICAN, 320.00, "beef_burrito.png")
                .needs(tortilla, 1).needs(beefCut, 140).needs(basmatiRice, 60).needs(cheese, 1));
        dishes.add(new Dish("Chicken Sub Sandwich", Dish.CAT_MEXICAN, 250.00, "chicken_sub_sandwich.png")
                .needs(subRoll, 1).needs(chicken, 100).needs(lettuce, 20).needs(cheese, 1));
        dishes.add(new Dish("Beef Sub Sandwich", Dish.CAT_MEXICAN, 280.00, "beef_sub_sandwich.png")
                .needs(subRoll, 1).needs(beefCut, 110).needs(lettuce, 20).needs(cheese, 1));

        // ================================================================================
        // 10. INTERNATIONAL SPECIALS  (Thai / Turkish / Saudi-Arabian / Pakistani / Indian /
        //                               Japanese / Italian - all grouped under one category,
        //                               same as the tree on the Menu tab and the Thursday
        //                               "International Thursday" discount)
        // ================================================================================
        // ---- Thai
        dishes.add(new Dish("Thai Chicken Curry", Dish.CAT_INTERNATIONAL, 350.00, "thai_chicken_curry.png")
                .needs(chicken, 200).needs(coconutMilk, 150).needs(curryPaste, 30));
        dishes.add(new Dish("Tom Yum Soup", Dish.CAT_INTERNATIONAL, 280.00, "tom_yum_soup.png")
                .needs(chickenStock, 300).needs(shrimp, 80).needs(curryPaste, 25).needs(lemon, 1));
        dishes.add(new Dish("Thai Basil Chicken", Dish.CAT_INTERNATIONAL, 320.00, "thai_basil_chicken.png")
                .needs(chicken, 200).needs(capsicum, 40).needs(curryPaste, 20).needs(garlic, 10));
        dishes.add(new Dish("Thai Green Curry", Dish.CAT_INTERNATIONAL, 350.00, "thai_green_curry.png")
                .needs(chicken, 200).needs(coconutMilk, 200).needs(curryPaste, 40).special());
        dishes.add(new Dish("Mango Sticky Rice", Dish.CAT_INTERNATIONAL, 220.00, "mango_sticky_rice.png")
                .needs(basmatiRice, 150).needs(mangoPulp, 100).needs(coconutMilk, 80).needs(sugar, 20));
        // ---- Turkish
        dishes.add(new Dish("Chicken Shawarma Plate", Dish.CAT_INTERNATIONAL, 350.00, "chicken_shawarma_plate.png")
                .needs(chicken, 200).needs(basmatiRice, 150).needs(yogurt, 30).needs(garlic, 8));
        dishes.add(new Dish("Turkish Chicken Kebab", Dish.CAT_INTERNATIONAL, 380.00, "turkish_chicken_kebab.png")
                .needs(chicken, 220).needs(onion, 30).needs(blackPepper, 4).needs(cookingOil, 15));
        dishes.add(new Dish("Adana Kebab", Dish.CAT_INTERNATIONAL, 420.00, "adana_kebab.png")
                .needs(beefCut, 200).needs(onion, 30).needs(blackPepper, 5).special());
        dishes.add(new Dish("Lamb Kebab", Dish.CAT_INTERNATIONAL, 480.00, "lamb_kebab.png")
                .needs(lambMeat, 220).needs(onion, 30).needs(garlic, 10).needs(blackPepper, 4));
        // ---- Saudi / Arabian
        dishes.add(new Dish("Chicken Kabsa", Dish.CAT_INTERNATIONAL, 420.00, "chicken_kabsa.png")
                .needs(basmatiRice, 200).needs(chicken, 200).needs(onion, 40).special());
        dishes.add(new Dish("Mutton Kabsa", Dish.CAT_INTERNATIONAL, 520.00, "mutton_kabsa.png")
                .needs(muttonMeat, 250).needs(basmatiRice, 200).needs(onion, 40));
        dishes.add(new Dish("Chicken Mandi", Dish.CAT_INTERNATIONAL, 450.00, "chicken_mandi.png")
                .needs(chicken, 220).needs(basmatiRice, 200).needs(onion, 30));
        dishes.add(new Dish("Mutton Mandi", Dish.CAT_INTERNATIONAL, 550.00, "mutton_mandi.png")
                .needs(muttonMeat, 250).needs(basmatiRice, 200).needs(yogurt, 50));
        dishes.add(new Dish("Beef Shawarma Plate", Dish.CAT_INTERNATIONAL, 420.00, "beef_shawarma_plate.png")
                .needs(beefCut, 200).needs(basmatiRice, 150).needs(yogurt, 30).needs(garlic, 8));
        dishes.add(new Dish("Hummus", Dish.CAT_INTERNATIONAL, 180.00, "hummus.png")
                .needs(chickpeas, 150).needs(tahini, 40).needs(garlic, 8).needs(cookingOil, 20));
        dishes.add(new Dish("Falafel Plate", Dish.CAT_INTERNATIONAL, 220.00, "falafel_plate.png")
                .needs(chickpeas, 180).needs(garlic, 10).needs(cookingOil, 30));
        dishes.add(new Dish("Arabic Grilled Chicken", Dish.CAT_INTERNATIONAL, 420.00, "arabic_grilled_chicken.png")
                .needs(chicken, 250).needs(garlic, 12).needs(blackPepper, 4).needs(cookingOil, 20));
        dishes.add(new Dish("Kunafa", Dish.CAT_INTERNATIONAL, 250.00, "kunafa.png")
                .needs(kunafaDough, 120).needs(cheese, 60).needs(sugarSyrup, 40).needs(ghee, 20).special());
        // ---- Pakistani
        dishes.add(new Dish("Beef Nihari", Dish.CAT_INTERNATIONAL, 400.00, "beef_nihari.png")
                .needs(beefCut, 250).needs(onion, 40).needs(ghee, 20).special());
        dishes.add(new Dish("Chicken Handi", Dish.CAT_INTERNATIONAL, 380.00, "chicken_handi.png")
                .needs(chicken, 220).needs(yogurt, 40).needs(onion, 30).needs(cream, 30));
        dishes.add(new Dish("Chicken Malai Tikka", Dish.CAT_INTERNATIONAL, 350.00, "chicken_malai_tikka.png")
                .needs(chicken, 220).needs(cream, 50).needs(yogurt, 30).needs(garlic, 8));
        dishes.add(new Dish("Seekh Kebab", Dish.CAT_INTERNATIONAL, 350.00, "seekh_kebab.png")
                .needs(beefCut, 200).needs(onion, 20).needs(garlic, 10).needs(blackPepper, 4));
        dishes.add(new Dish("Reshmi Kebab", Dish.CAT_INTERNATIONAL, 350.00, "reshmi_kebab.png")
                .needs(chicken, 200).needs(cream, 40).needs(yogurt, 30).needs(garlic, 8));
        dishes.add(new Dish("Haleem", Dish.CAT_INTERNATIONAL, 280.00, "haleem.png")
                .needs(muttonMeat, 150).needs(lentils, 100).needs(basmatiRice, 50).needs(ghee, 15));
        dishes.add(new Dish("Peshawari Naan", Dish.CAT_INTERNATIONAL, 180.00, "peshawari_naan.png")
                .needs(naanDough, 1).needs(sugar, 15).needs(ghee, 10));
        dishes.add(new Dish("Pakistani Kheer", Dish.CAT_INTERNATIONAL, 150.00, "pakistani_kheer.png")
                .needs(milk, 300).needs(basmatiRice, 40).needs(sugar, 30));
        // ---- Indian
        dishes.add(new Dish("Butter Chicken", Dish.CAT_INTERNATIONAL, 350.00, "butter_chicken.png")
                .needs(chicken, 200).needs(butter, 30).needs(cream, 60).needs(tomato, 80).special());
        dishes.add(new Dish("Chicken Tikka Masala", Dish.CAT_INTERNATIONAL, 360.00, "chicken_tikka_masala.png")
                .needs(chicken, 220).needs(tomato, 60).needs(cream, 50).needs(garlic, 10));
        // NOTE: the menu lists "Chicken Tandoori" twice (Chicken Specials Tt220 and here Tt320).
        // Renamed to "Chicken Tandoori (Indian)" so both stay distinct and orderable by name.
        dishes.add(new Dish("Chicken Tandoori (Indian)", Dish.CAT_INTERNATIONAL, 320.00, "chicken_tandoori_indian.png")
                .needs(chicken, 220).needs(yogurt, 60).needs(garlic, 10).needs(blackPepper, 3));
        dishes.add(new Dish("Palak Paneer", Dish.CAT_INTERNATIONAL, 300.00, "palak_paneer.png")
                .needs(spinach, 200).needs(paneer, 120).needs(cream, 30).needs(garlic, 8));
        dishes.add(new Dish("Dal Makhani", Dish.CAT_INTERNATIONAL, 220.00, "dal_makhani.png")
                .needs(lentils, 200).needs(butter, 20).needs(cream, 30).needs(garlic, 8));
        dishes.add(new Dish("Chole Bhature", Dish.CAT_INTERNATIONAL, 250.00, "chole_bhature.png")
                .needs(chickpeas, 200).needs(naanDough, 1).needs(onion, 30).needs(tomato, 30));
        // ---- Japanese
        dishes.add(new Dish("Chicken Ramen", Dish.CAT_INTERNATIONAL, 350.00, "chicken_ramen.png")
                .needs(ramenNoodles, 150).needs(chicken, 120).needs(soySauce, 30).needs(egg, 1).special());
        dishes.add(new Dish("Beef Ramen", Dish.CAT_INTERNATIONAL, 400.00, "beef_ramen.png")
                .needs(ramenNoodles, 150).needs(beefCut, 130).needs(soySauce, 25).needs(egg, 1));
        dishes.add(new Dish("Chicken Teriyaki", Dish.CAT_INTERNATIONAL, 380.00, "chicken_teriyaki.png")
                .needs(chicken, 220).needs(teriyakiSauce, 40).needs(garlic, 8));
        dishes.add(new Dish("Beef Teriyaki", Dish.CAT_INTERNATIONAL, 420.00, "beef_teriyaki.png")
                .needs(beefCut, 220).needs(teriyakiSauce, 40).needs(garlic, 8));
        dishes.add(new Dish("Vegetable Sushi", Dish.CAT_INTERNATIONAL, 300.00, "vegetable_sushi.png")
                .needs(sushiRice, 150).needs(nori, 3).needs(mixedVeg, 60).needs(cucumber, 40));
        // ---- Italian
        dishes.add(new Dish("Chicken Alfredo Pasta", Dish.CAT_INTERNATIONAL, 350.00, "chicken_alfredo_pasta.png")
                .needs(spaghetti, 200).needs(chicken, 150).needs(cream, 100));
        dishes.add(new Dish("Tiramisu", Dish.CAT_INTERNATIONAL, 250.00, "tiramisu.png")
                .needs(mascarpone, 120).needs(coffeePowder, 15).needs(cocoaPowder, 10).needs(egg, 1));
        dishes.add(new Dish("Lasagna", Dish.CAT_INTERNATIONAL, 420.00, "lasagna.png")
                .needs(lasagnaSheets, 4).needs(beefCut, 150).needs(cheese, 3).needs(sauce, 100).special());

        // ================================================================================
        // 11. SALAD
        // ================================================================================
        dishes.add(new Dish("Green Salad", Dish.CAT_SALAD, 120.00, "green_salad.png")
                .needs(lettuce, 100).needs(cucumber, 60).needs(tomato, 60));
        dishes.add(new Dish("Chicken Salad", Dish.CAT_SALAD, 220.00, "chicken_salad.png")
                .needs(lettuce, 100).needs(chicken, 100).needs(tomato, 50).needs(mayonnaise, 20));
        dishes.add(new Dish("Russian Salad", Dish.CAT_SALAD, 180.00, "russian_salad.png")
                .needs(potato, 150).needs(mayonnaise, 40).needs(mixedVeg, 60));
        dishes.add(new Dish("Fruit Salad", Dish.CAT_SALAD, 180.00, "fruit_salad.png")
                .needs(mixedFruit, 200));
        dishes.add(new Dish("Corn Salad", Dish.CAT_SALAD, 150.00, "corn_salad.png")
                .needs(sweetCorn, 150).needs(capsicum, 40).needs(mayonnaise, 20));
        dishes.add(new Dish("Special House Salad", Dish.CAT_SALAD, 250.00, "special_house_salad.png")
                .needs(lettuce, 100).needs(chicken, 80).needs(cheese, 1).needs(tomato, 50).needs(cucumber, 40));

        // ================================================================================
        // 12. DESSERTS
        // ================================================================================
        dishes.add(new Dish("Chocolate Cake", Dish.CAT_DESSERTS, 160.00, "chocolate_cake.png")
                .needs(flour, 100).needs(sugar, 60).needs(cocoaPowder, 30).needs(egg, 1).needs(butter, 30));
        dishes.add(new Dish("Black Forest Cake", Dish.CAT_DESSERTS, 180.00, "black_forest_cake.png")
                .needs(flour, 100).needs(sugar, 60).needs(cocoaPowder, 25).needs(cream, 40).needs(egg, 1));
        dishes.add(new Dish("Red Velvet Cake", Dish.CAT_DESSERTS, 180.00, "red_velvet_cake.png")
                .needs(flour, 100).needs(sugar, 60).needs(cocoaPowder, 15).needs(creamCheese, 40).needs(egg, 1));
        dishes.add(new Dish("Chocolate Brownie", Dish.CAT_DESSERTS, 180.00, "chocolate_brownie.png")
                .needs(flour, 80).needs(sugar, 60).needs(cocoaPowder, 30).needs(butter, 30).needs(egg, 1));
        dishes.add(new Dish("Chocolate Lava Cake", Dish.CAT_DESSERTS, 220.00, "chocolate_lava_cake.png")
                .needs(flour, 60).needs(sugar, 40).needs(cocoaPowder, 30).needs(butter, 40).needs(egg, 1));
        dishes.add(new Dish("Cheesecake", Dish.CAT_DESSERTS, 250.00, "cheesecake.png")
                .needs(creamCheese, 150).needs(sugar, 50).needs(flour, 20).needs(egg, 1));
        dishes.add(new Dish("Donut", Dish.CAT_DESSERTS, 100.00, "donut.png")
                .needs(flour, 80).needs(sugar, 30).needs(cookingOil, 30).needs(egg, 1));
        dishes.add(new Dish("Chocolate Donut", Dish.CAT_DESSERTS, 130.00, "chocolate_donut.png")
                .needs(flour, 80).needs(sugar, 30).needs(cocoaPowder, 15).needs(cookingOil, 30));
        dishes.add(new Dish("Waffle", Dish.CAT_DESSERTS, 180.00, "waffle.png")
                .needs(flour, 100).needs(sugar, 30).needs(butter, 20).needs(egg, 1).needs(milk, 60));
        dishes.add(new Dish("Chocolate Waffle", Dish.CAT_DESSERTS, 220.00, "chocolate_waffle.png")
                .needs(flour, 100).needs(sugar, 30).needs(cocoaPowder, 20).needs(butter, 20).needs(milk, 60));
        dishes.add(new Dish("Ice Cream", Dish.CAT_DESSERTS, 120.00, "ice_cream.png")
                .needs(vanillaIceCream, 150));
        dishes.add(new Dish("Chocolate Sundae", Dish.CAT_DESSERTS, 220.00, "chocolate_sundae.png")
                .needs(vanillaIceCream, 150).needs(chocolateSyrup, 40));
        dishes.add(new Dish("Strawberry Sundae", Dish.CAT_DESSERTS, 200.00, "strawberry_sundae.png")
                .needs(vanillaIceCream, 150).needs(strawberrySyrup, 40));
        dishes.add(new Dish("Falooda", Dish.CAT_DESSERTS, 180.00, "falooda.png")
                .needs(faloodaMix, 80).needs(milk, 150).needs(sugarSyrup, 30).needs(vanillaIceCream, 60));
        dishes.add(new Dish("Fruit Custard", Dish.CAT_DESSERTS, 150.00, "fruit_custard.png")
                .needs(milk, 200).needs(mixedFruit, 80).needs(sugar, 20));
        dishes.add(new Dish("Caramel Pudding", Dish.CAT_DESSERTS, 150.00, "caramel_pudding.png")
                .needs(milk, 150).needs(egg, 1).needs(caramelSyrup, 30).needs(sugar, 20));

        // ================================================================================
        // 13. COLD DRINKS
        // ================================================================================
        // NOTE: the menu's plain "Cold Drinks - Tt30" line (a generic soft drink/soda) is
        // named "Soft Drink" here so it doesn't share its name with the category itself.
        dishes.add(new Dish("Soft Drink", Dish.CAT_COLD_DRINKS, 30.00, "soft_drink.png")
                .needs(colaSyrup, 50).needs(bottledWater, 1));
        dishes.add(new Dish("Mineral Water", Dish.CAT_COLD_DRINKS, 20.00, "mineral_water.png")
                .needs(bottledWater, 1));
        dishes.add(new Dish("Fresh Lemonade", Dish.CAT_COLD_DRINKS, 60.00, "fresh_lemonade.png")
                .needs(lemon, 2).needs(sugar, 20).needs(bottledWater, 1));
        dishes.add(new Dish("Mint Lemonade", Dish.CAT_COLD_DRINKS, 70.00, "mint_lemonade.png")
                .needs(lemon, 2).needs(mintLeaves, 10).needs(sugar, 20).needs(bottledWater, 1));
        dishes.add(new Dish("Orange Juice", Dish.CAT_COLD_DRINKS, 60.00, "orange_juice.png")
                .needs(orange, 3));
        dishes.add(new Dish("Mango Juice", Dish.CAT_COLD_DRINKS, 80.00, "mango_juice.png")
                .needs(mangoPulp, 200).needs(sugar, 15));
        dishes.add(new Dish("Watermelon Juice", Dish.CAT_COLD_DRINKS, 80.00, "watermelon_juice.png")
                .needs(watermelon, 300));
        dishes.add(new Dish("Pineapple Juice", Dish.CAT_COLD_DRINKS, 90.00, "pineapple_juice.png")
                .needs(pineapple, 200).needs(sugar, 10));
        dishes.add(new Dish("Apple Juice", Dish.CAT_COLD_DRINKS, 80.00, "apple_juice.png")
                .needs(apple, 3));

        // ================================================================================
        // 14. MILKSHAKES & SPECIAL DRINKS
        // ================================================================================
        dishes.add(new Dish("Vanilla Milkshake", Dish.CAT_MILKSHAKES, 180.00, "vanilla_milkshake.png")
                .needs(milk, 200).needs(vanillaIceCream, 100));
        dishes.add(new Dish("Chocolate Milkshake", Dish.CAT_MILKSHAKES, 200.00, "chocolate_milkshake.png")
                .needs(milk, 200).needs(vanillaIceCream, 80).needs(chocolateSyrup, 40));
        dishes.add(new Dish("Strawberry Milkshake", Dish.CAT_MILKSHAKES, 200.00, "strawberry_milkshake.png")
                .needs(milk, 200).needs(vanillaIceCream, 80).needs(strawberrySyrup, 40));
        dishes.add(new Dish("Mango Milkshake", Dish.CAT_MILKSHAKES, 200.00, "mango_milkshake.png")
                .needs(milk, 200).needs(vanillaIceCream, 60).needs(mangoPulp, 100));
        dishes.add(new Dish("Oreo Shake", Dish.CAT_MILKSHAKES, 220.00, "oreo_shake.png")
                .needs(milk, 200).needs(vanillaIceCream, 80).needs(oreoCookies, 4));
        dishes.add(new Dish("KitKat Shake", Dish.CAT_MILKSHAKES, 230.00, "kitkat_shake.png")
                .needs(milk, 200).needs(vanillaIceCream, 80).needs(kitkatBar, 1));
        dishes.add(new Dish("Chocolate Brownie Shake", Dish.CAT_MILKSHAKES, 250.00, "chocolate_brownie_shake.png")
                .needs(milk, 200).needs(vanillaIceCream, 80).needs(browniePiece, 1).needs(chocolateSyrup, 30));
        dishes.add(new Dish("Cold Coffee", Dish.CAT_MILKSHAKES, 160.00, "cold_coffee.png")
                .needs(milk, 200).needs(coffeePowder, 15).needs(sugar, 15));
        dishes.add(new Dish("Iced Latte", Dish.CAT_MILKSHAKES, 180.00, "iced_latte.png")
                .needs(milk, 150).needs(coffeePowder, 12));
        dishes.add(new Dish("Iced Mocha", Dish.CAT_MILKSHAKES, 200.00, "iced_mocha.png")
                .needs(milk, 150).needs(coffeePowder, 12).needs(chocolateSyrup, 30));

        // ================================================================================
        // 15. HOT BEVERAGES
        // ================================================================================
        dishes.add(new Dish("Tea", Dish.CAT_HOT_BEVERAGES, 30.00, "tea.png")
                .needs(teaLeaves, 5).needs(milk, 30).needs(sugar, 10));
        dishes.add(new Dish("Milk Tea", Dish.CAT_HOT_BEVERAGES, 50.00, "milk_tea.png")
                .needs(teaLeaves, 5).needs(milk, 100).needs(sugar, 15));
        dishes.add(new Dish("Masala Tea", Dish.CAT_HOT_BEVERAGES, 40.00, "masala_tea.png")
                .needs(teaLeaves, 5).needs(milk, 80).needs(teaMasala, 5).needs(sugar, 15));
        dishes.add(new Dish("Lemon Tea", Dish.CAT_HOT_BEVERAGES, 40.00, "lemon_tea.png")
                .needs(teaLeaves, 5).needs(lemon, 1).needs(sugar, 10));
        dishes.add(new Dish("Green Tea", Dish.CAT_HOT_BEVERAGES, 40.00, "green_tea.png")
                .needs(teaLeaves, 5));
        dishes.add(new Dish("Black Coffee", Dish.CAT_HOT_BEVERAGES, 60.00, "black_coffee.png")
                .needs(coffeePowder, 10).needs(sugar, 10));
        dishes.add(new Dish("Espresso", Dish.CAT_HOT_BEVERAGES, 100.00, "espresso.png")
                .needs(coffeePowder, 15));
        dishes.add(new Dish("Cappuccino", Dish.CAT_HOT_BEVERAGES, 120.00, "cappuccino.png")
                .needs(coffeePowder, 15).needs(milk, 100));
        dishes.add(new Dish("Cafe Latte", Dish.CAT_HOT_BEVERAGES, 160.00, "cafe_latte.png")
                .needs(coffeePowder, 12).needs(milk, 150));
        dishes.add(new Dish("Americano", Dish.CAT_HOT_BEVERAGES, 150.00, "americano.png")
                .needs(coffeePowder, 15));
        dishes.add(new Dish("Mocha", Dish.CAT_HOT_BEVERAGES, 160.00, "mocha.png")
                .needs(coffeePowder, 12).needs(milk, 100).needs(cocoaPowder, 15));

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
        // first run ever (or database unavailable) -> create the default staff roster:
        // 2 original team members + 15 new hires (5 Chefs, 10 Servers), all Bangladeshi.
        addStaff(new Person("Rahim Uddin", "Male", "Expert", "Bangladesh",
                LocalDate.of(1985, 4, 12), "Reading, Traveling", "-", "Chef"));
        addStaff(new Person("Ayesha Rahman", "Female", "Intermediate", "Bangladesh",
                LocalDate.of(1994, 9, 3), "Gaming", "-", "Server"));

        // ---- 5 new Chefs ------------------------------------------------------------------
        addStaff(new Person("Kamal Hossain", "Male", "Expert", "Bangladesh",
                LocalDate.of(1982, 6, 18), "Reading", "-", "Chef"));
        addStaff(new Person("Nasrin Akter", "Female", "Expert", "Bangladesh",
                LocalDate.of(1988, 11, 2), "Traveling", "-", "Chef"));
        addStaff(new Person("Shakil Ahmed", "Male", "Intermediate", "Bangladesh",
                LocalDate.of(1991, 2, 27), "Gaming, Traveling", "-", "Chef"));
        addStaff(new Person("Farida Yasmin", "Female", "Expert", "Bangladesh",
                LocalDate.of(1986, 8, 9), "Reading, Gaming", "-", "Chef"));
        addStaff(new Person("Mizanur Rahman", "Male", "Intermediate", "Bangladesh",
                LocalDate.of(1993, 5, 30), "Traveling", "-", "Chef"));

        // ---- 10 new Servers ---------------------------------------------------------------
        addStaff(new Person("Sabbir Hossain", "Male", "Beginner", "Bangladesh",
                LocalDate.of(1999, 3, 14), "Gaming", "-", "Server"));
        addStaff(new Person("Runa Islam", "Female", "Beginner", "Bangladesh",
                LocalDate.of(2000, 7, 21), "Reading", "-", "Server"));
        addStaff(new Person("Tanvir Ahmed", "Male", "Intermediate", "Bangladesh",
                LocalDate.of(1997, 12, 5), "Traveling", "-", "Server"));
        addStaff(new Person("Moushumi Akter", "Female", "Beginner", "Bangladesh",
                LocalDate.of(2001, 4, 17), "Gaming, Reading", "-", "Server"));
        addStaff(new Person("Jashim Uddin", "Male", "Beginner", "Bangladesh",
                LocalDate.of(1998, 9, 23), "Traveling", "-", "Server"));
        addStaff(new Person("Shirin Sultana", "Female", "Intermediate", "Bangladesh",
                LocalDate.of(1996, 1, 30), "Reading", "-", "Server"));
        addStaff(new Person("Rakibul Islam", "Male", "Beginner", "Bangladesh",
                LocalDate.of(2002, 6, 11), "Gaming", "-", "Server"));
        addStaff(new Person("Nusrat Jahan", "Female", "Beginner", "Bangladesh",
                LocalDate.of(1999, 10, 8), "Traveling, Reading", "-", "Server"));
        addStaff(new Person("Delwar Hossain", "Male", "Intermediate", "Bangladesh",
                LocalDate.of(1995, 2, 19), "Gaming", "-", "Server"));
        addStaff(new Person("Poly Begum", "Female", "Beginner", "Bangladesh",
                LocalDate.of(2000, 12, 25), "Reading", "-", "Server"));
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
                dob, (String) row[6], (String) row[7], (String) row[8]);
        person.setId(id);
        staff.add(person);
    }

    // ------------------------------------------------------------------ STAFF CRUD (Create/Read/Update/Delete)

    /** CREATE: adds a staff member to the in-memory table AND persists it in SQLite. */
    public void addStaff(Person person) {
        staff.add(person);
        int id = DatabaseManager.insertStaff(person.getName(), person.getGender(), person.getSkillLevel(),
                person.getCountry(), person.getDateOfBirth() == null ? "" : person.getDateOfBirth().toString(),
                person.getHobbies(), person.getPhotoFile(), person.getRole());
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
