package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.model.RecipeLine;
import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * "Orders" tab - the heart of the project.
 *
 * Pick a dish -> see the ingredients it needs -> choose a quantity -> Place Order
 * -> the ingredients are deducted from stock and out-of-stock items are shown.
 *
 * TOPICS HERE: ListView, ImageView + "Change Image", Spinner (1-10),
 *              event handling from code (Button.setOnAction, TextField + Enter key),
 *              TableView (recipe), Alerts, Initializable.
 */
public class OrderController implements Initializable {

    // ---- injected from OrderView.fxml
    @FXML private HBox quickBar;              // filled with a TextField + Buttons created in CODE
    @FXML private HBox actionBar;             // the "Place Order" Button is created in CODE and added here
    @FXML private ListView<Dish> dishList;
    @FXML private ListView<String> historyList;
    @FXML private ImageView dishImage;
    @FXML private Label selectedDishLabel;
    @FXML private Label orderSummaryLabel;
    @FXML private Label stockAlertLabel;
    @FXML private Spinner<Integer> qtySpinner;
    @FXML private TableView<RecipeLine> recipeTable;
    @FXML private TableColumn<RecipeLine, String> recIngredientCol;
    @FXML private TableColumn<RecipeLine, String> recNeededCol;
    @FXML private TableColumn<RecipeLine, String> recInStockCol;
    @FXML private TableColumn<RecipeLine, String> recStatusCol;

    private final InventoryService service = InventoryService.getInstance();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ---------- Spinner: numeric quantity 1..10, start value 1
        qtySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));

        // ---------- ListView of dishes (out-of-stock dishes are shown in red)
        dishList.setItems(service.getDishes());
        dishList.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(Dish dish, boolean empty) {
                super.updateItem(dish, empty);
                if (empty || dish == null) {
                    setText(null);
                    setStyle("");
                } else if (dish.isAvailable()) {
                    setText(String.format("%s   -   $%.2f", dish.getName(), dish.getPrice()));
                    setStyle("");
                } else {
                    setText(dish.getName() + "   [OUT OF STOCK]");
                    setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
                }
            }
        });
        dishList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldDish, newDish) -> showDish(newDish));

        historyList.setItems(service.getOrderHistory());

        setupRecipeTable();
        buildQuickSearchBar();   // TextField + Enter key, created in code
        buildPlaceOrderButton(); // Button + setOnAction, created in code

        // Bottom label always shows what is out of stock
        stockAlertLabel.textProperty().bind(service.stockAlertProperty());

        // Whenever any stock level changes -> repaint the list, table and title
        service.stockVersionProperty().addListener((observable, oldValue, newValue) -> {
            dishList.refresh();
            refreshSelectedLabel();
        });

        dishList.getSelectionModel().selectFirst();
    }

    // ================================================================= EVENT HANDLING FROM CODE

    /** Quick search: a TextField created in Java code that reacts to the ENTER key. */
    private void buildQuickSearchBar() {
        Label title = new Label("Quick find:");
        TextField searchField = new TextField();
        searchField.setPromptText("type part of a dish name and press Enter");
        searchField.setPrefWidth(320);
        Label resultLabel = new Label();

        // EVENT: fires when a key is pressed inside the TextField - we only react to ENTER
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                String text = searchField.getText().trim().toLowerCase();
                if (text.isEmpty()) {
                    resultLabel.setText("Type something first.");
                    return;
                }
                Dish found = service.getDishes().stream()
                        .filter(dish -> dish.getName().toLowerCase().contains(text))
                        .findFirst()
                        .orElse(null);
                if (found == null) {
                    resultLabel.setText("No dish matches \"" + text + "\".");
                } else {
                    dishList.getSelectionModel().select(found);
                    dishList.scrollTo(found);
                    resultLabel.setText("Found: " + found.getName());
                }
            }
        });

        // A Button created in code, with its handler attached by setOnAction()
        Button clearButton = new Button("Clear");
        clearButton.setOnAction(event -> {
            searchField.clear();
            resultLabel.setText("");
            searchField.requestFocus();
        });

        quickBar.getChildren().addAll(title, searchField, clearButton, resultLabel);
    }

    /** The main action button is also created in code. */
    private void buildPlaceOrderButton() {
        Button placeOrderButton = new Button("Place Order");
        placeOrderButton.getStyleClass().add("primary-button");
        placeOrderButton.setOnAction(event -> placeOrder());   // <-- setOnAction()
        actionBar.getChildren().add(placeOrderButton);
    }

    // ================================================================= ORDER LOGIC

    private void placeOrder() {
        Dish dish = dishList.getSelectionModel().getSelectedItem();
        if (dish == null) {
            AlertUtil.warning("No dish selected", "Please select a dish from the menu first.");
            return;
        }

        int quantity = qtySpinner.getValue();               // SPINNER value
        InventoryService.OrderResult result = service.placeOrder(dish, quantity);

        if (result.success()) {
            orderSummaryLabel.setStyle("-fx-text-fill: #1e8449; -fx-font-weight: bold;");
            orderSummaryLabel.setText(result.message());

            // Warn if this order used up an ingredient completely
            List<String> nowEmpty = dish.getRecipe().stream()
                    .map(RecipeLine::getIngredient)
                    .filter(Ingredient::isOutOfStock)
                    .map(Ingredient::getName)
                    .toList();
            if (!nowEmpty.isEmpty()) {
                AlertUtil.warning("Ingredient ran out",
                        "These ingredients are now OUT OF STOCK:\n" + String.join(", ", nowEmpty));
            }
        } else {
            orderSummaryLabel.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
            orderSummaryLabel.setText(result.message());
            AlertUtil.error("Out of stock", result.message());
        }
    }

    // ================================================================= SELECTED DISH VIEW

    private void showDish(Dish dish) {
        if (dish == null) {
            dishImage.setImage(null);
            recipeTable.setItems(FXCollections.observableArrayList());
            refreshSelectedLabel();
            return;
        }
        dishImage.setImage(loadResourceImage(dish.getImageName()));
        recipeTable.setItems(FXCollections.observableArrayList(dish.getRecipe()));
        refreshSelectedLabel();
    }

    private void refreshSelectedLabel() {
        Dish dish = dishList.getSelectionModel().getSelectedItem();
        if (dish == null) {
            selectedDishLabel.setText("Select a dish from the menu");
            return;
        }
        selectedDishLabel.setText(String.format("%s   -   $%.2f%s",
                dish.getName(), dish.getPrice(), dish.isAvailable() ? "" : "   [OUT OF STOCK]"));
    }

    // ================================================================= IMAGEVIEW

    /** "Change Image" button: load the next picture from the resources/images directory. */
    @FXML
    private void onChangeImage() {
        Dish dish = dishList.getSelectionModel().getSelectedItem();
        if (dish == null) {
            AlertUtil.warning("No dish selected", "Select a dish first, then change its image.");
            return;
        }
        List<String> files = Dish.IMAGE_FILES;
        int next = (files.indexOf(dish.getImageName()) + 1) % files.size();
        dish.setImageName(files.get(next));
        dishImage.setImage(loadResourceImage(dish.getImageName()));
    }

    private Image loadResourceImage(String fileName) {
        URL url = getClass().getResource("/images/" + fileName);   // src/main/resources/images/...
        return url == null ? null : new Image(url.toExternalForm());
    }

    // ================================================================= RECIPE TABLE

    private int currentQty() {
        Integer value = qtySpinner.getValue();
        return value == null ? 1 : value;
    }

    /** Shows what the selected dish needs vs. what is in stock (updates live). */
    private void setupRecipeTable() {
        recIngredientCol.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().getIngredient().getName()));

        recNeededCol.setCellValueFactory(cell -> {
            RecipeLine line = cell.getValue();
            return Bindings.createStringBinding(
                    () -> Ingredient.formatAmount(line.getAmount() * currentQty())
                            + " " + line.getIngredient().getUnit(),
                    qtySpinner.valueProperty());
        });

        recInStockCol.setCellValueFactory(cell -> {
            Ingredient ingredient = cell.getValue().getIngredient();
            return Bindings.createStringBinding(
                    () -> Ingredient.formatAmount(ingredient.getQuantity()) + " " + ingredient.getUnit(),
                    ingredient.quantityProperty());
        });

        recStatusCol.setCellValueFactory(cell -> {
            RecipeLine line = cell.getValue();
            return Bindings.createStringBinding(
                    () -> line.getIngredient().getQuantity() >= line.getAmount() * currentQty()
                            ? "OK" : "NOT ENOUGH",
                    line.getIngredient().quantityProperty(), qtySpinner.valueProperty());
        });

        recStatusCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    setStyle("OK".equals(status)
                            ? "-fx-text-fill: #1e8449; -fx-font-weight: bold;"
                            : "-fx-text-fill: #c0392b; -fx-font-weight: bold;");
                }
            }
        });
    }
}
