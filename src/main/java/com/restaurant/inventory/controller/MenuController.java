package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.RecipeLine;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.model.WeatherInfo;
import com.restaurant.inventory.model.WeatherOffer;
import com.restaurant.inventory.service.InventoryService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * "Menu" tab: the menu organised as a tree (categories -> dishes).
 *
 * TOPIC HERE: TreeView - the selected node's name is shown in a Label.
 *
 * Also shows Khulna's LIVE WEATHER (Open-Meteo API, fetched on a background thread at start-up)
 * with a weather deal: a few dishes that suit the weather are suggested and discounted.
 * Clicking a suggested dish jumps to it in the tree.
 */
public class MenuController implements Initializable {

    @FXML private TreeView<String> menuTree;
    @FXML private Label selectedNodeLabel;
    @FXML private Label detailsLabel;
    @FXML private Label todaysDiscountLabel;

    // weather card
    @FXML private Label weatherTitleLabel;
    @FXML private Label weatherDetailsLabel;
    @FXML private Label weatherOfferLabel;
    @FXML private FlowPane weatherPicksPane;
    @FXML private Button refreshWeatherButton;

    private final InventoryService service = InventoryService.getInstance();
    /** Non-null when the last weather request failed (shown instead of the weather). */
    private String weatherError;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        refreshBanner();
        buildTree();
        colorizeTree();

        selectedNodeLabel.setText("Selected node: (click something in the tree)");
        detailsLabel.setText("");

        // TreeView selection -> show the node's name in a Label
        menuTree.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldItem, newItem) -> showNode(newItem));

        // keep the availability text up to date when stock changes
        service.stockVersionProperty().addListener((observable, oldValue, newValue) -> {
            showNode(menuTree.getSelectionModel().getSelectedItem());
            refreshBanner();
            renderWeather();
        });

        // weather: repaint the card whenever new weather arrives, and fetch it once at start-up
        service.weatherProperty().addListener((observable, oldValue, newValue) -> {
            weatherError = null;
            renderWeather();
        });
        renderWeather();
        if (service.getWeather() == null && !service.isWeatherLoading()) {
            loadWeather();
        }
    }

    // ================================================================= LIVE WEATHER (Open-Meteo)

    private void refreshBanner() {
        if (todaysDiscountLabel != null) {
            todaysDiscountLabel.setText("\uD83C\uDF89  " + service.getTodayDiscountText() + service.getWeatherBannerText());
        }
    }

    @FXML
    private void onRefreshWeather() {
        if (!service.isWeatherLoading()) {
            loadWeather();
        }
    }

    /** Runs the HTTP request on the shared thread pool so the window never freezes. */
    private void loadWeather() {
        refreshWeatherButton.setDisable(true);
        weatherError = null;
        if (service.getWeather() == null) {
            renderWeather();   // shows "Loading..."
        }
        Task<WeatherInfo> task = service.fetchWeatherTask();
        task.setOnSucceeded(event -> refreshWeatherButton.setDisable(false));
        task.setOnFailed(event -> {
            weatherError = "Could not reach the weather service (check your internet connection).";
            refreshWeatherButton.setDisable(false);
            renderWeather();
        });
        service.getExecutor().submit(task);
    }

    /** Fills the weather card: conditions, the weather deal, and one clickable button per suggested dish. */
    private void renderWeather() {
        weatherPicksPane.getChildren().clear();
        WeatherInfo weather = service.getWeather();

        if (weather == null) {
            weatherTitleLabel.setText("\uD83C\uDF21  Khulna weather");
            weatherDetailsLabel.setText(weatherError != null ? weatherError : "Loading today's weather...");
            weatherOfferLabel.setText(weatherError != null ? "Weather deals are off until the weather loads." : "");
            return;
        }

        weatherTitleLabel.setText(String.format("%s  %s today: %.1f\u00B0C (feels like %.1f\u00B0C) - %s",
                weather.emoji(), weather.place(), weather.tempC(), weather.feelsLikeC(), weather.condition()));
        weatherDetailsLabel.setText(String.format("Humidity %d%%    Wind %.0f km/h    Rain %.1f mm    Updated %s%s",
                weather.humidity(), weather.windKmh(), weather.precipMm(),
                weather.fetchedAt().format(DateTimeFormatter.ofPattern("HH:mm")),
                weatherError != null ? "    (last refresh failed)" : ""));

        WeatherOffer offer = service.getActiveWeatherOffer();
        if (offer == null) {
            weatherOfferLabel.setText("This weather reading is old - press \"Refresh weather\" for a new weather deal.");
            return;
        }
        List<Dish> picks = service.getWeatherPicks();
        weatherOfferLabel.setText(String.format("%s  %s: %d%% OFF these dishes%s", offer.message(),
                offer.promoName(), offer.percent(),
                picks.isEmpty() ? " (all of them are out of stock right now)." : " (click one to see it):"));

        for (Dish dish : picks) {
            Button chip = new Button(dish.getName() + "  " + InventoryService.taka(service.getDiscountedPrice(dish)));
            chip.getStyleClass().add("weather-chip");
            chip.setOnAction(event -> selectDishInTree(dish.getName()));
            weatherPicksPane.getChildren().add(chip);
        }
    }

    private void selectDishInTree(String dishName) {
        for (TreeItem<String> category : menuTree.getRoot().getChildren()) {
            for (TreeItem<String> item : category.getChildren()) {
                if (dishName.equals(item.getValue())) {
                    menuTree.getSelectionModel().select(item);
                    menuTree.scrollTo(menuTree.getRow(item));
                    return;
                }
            }
        }
    }

    /** Root -> categories (Starters, Main Course, ...) -> dishes. */
    private void buildTree() {
        TreeItem<String> rootItem = new TreeItem<>("Restaurant Menu");
        rootItem.setExpanded(true);

        Map<String, TreeItem<String>> categories = new LinkedHashMap<>();
        for (Dish dish : service.getDishes()) {
            TreeItem<String> categoryItem = categories.get(dish.getCategory());
            if (categoryItem == null) {
                categoryItem = new TreeItem<>(dish.getCategory());
                categoryItem.setExpanded(true);
                categories.put(dish.getCategory(), categoryItem);
                rootItem.getChildren().add(categoryItem);
            }
            categoryItem.getChildren().add(new TreeItem<>(dish.getName()));
        }
        menuTree.setRoot(rootItem);
    }

    /** Colours each dish/category node's text using the same category colours as the Orders tab. */
    private void colorizeTree() {
        menuTree.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll(Dish.ALL_CATEGORY_STYLE_CLASSES);
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                Dish dish = service.findDish(value);
                if (dish != null) {
                    getStyleClass().add(Dish.categoryStyleClass(dish.getCategory()));
                }
            }
        });
    }

    private void showNode(TreeItem<String> item) {
        if (item == null) {
            return;
        }
        String name = item.getValue();
        selectedNodeLabel.setText("Selected node: " + name);

        Dish dish = service.findDish(name);
        if (dish != null) {
            detailsLabel.setText(describe(dish));
        } else if (item.getParent() == null) {
            detailsLabel.setText("This is the root of the menu.\nIt contains "
                    + item.getChildren().size() + " categories.");
        } else {
            detailsLabel.setText("Category \"" + name + "\" contains "
                    + item.getChildren().size() + " dish(es).");
        }
    }

    private String describe(Dish dish) {
        StringBuilder text = new StringBuilder();
        text.append("Category: ").append(dish.getCategory()).append('\n');
        if (dish.isChefSpecial()) {
            text.append("\u2B50 Chef's Special\n");
        }
        if (service.hasDiscountToday(dish)) {
            text.append(String.format("Price: %s  ->  %s  (-%d%% today: %s)%n",
                    InventoryService.taka(dish.getPrice()),
                    InventoryService.taka(service.getDiscountedPrice(dish)),
                    service.getDiscountPercent(dish),
                    service.getDiscountLabel(dish)));
        } else {
            text.append(String.format("Price: %s%n", InventoryService.taka(dish.getPrice())));
        }

        List<String> missing = dish.missingIngredients(1);
        if (missing.isEmpty()) {
            text.append("Availability: AVAILABLE\n");
        } else {
            text.append("Availability: OUT OF STOCK\n");
            text.append("Missing: ").append(String.join(", ", missing)).append('\n');
        }

        text.append("\nRecipe (per serving):\n");
        for (RecipeLine line : dish.getRecipe()) {
            text.append("  - ").append(line.getIngredient().getName()).append(": ")
                    .append(Ingredient.formatAmount(line.getAmount())).append(' ')
                    .append(line.getIngredient().getUnit()).append('\n');
        }
        return text.toString();
    }
}
