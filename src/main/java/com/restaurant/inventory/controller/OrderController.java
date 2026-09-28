package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.CartLine;
import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.model.OrderRecord;
import com.restaurant.inventory.model.RecipeLine;
import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import com.restaurant.inventory.util.BillDialog;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * "Orders" tab - the heart of the project.
 *
 * A customer can now order SEVERAL different dishes in one visit: type a dish name (or select
 * it from the menu), choose a quantity, click "Add to Cart" - repeat for every dish wanted -
 * then enter the customer's name and click "Place Order (Checkout)": the customer picks a payment
 * method (Cash / bKash / Nagad / Rocket / Card), everything is deducted from stock in one transaction,
 * a bill paper (itemised, in Taka, with today's discount) is shown for printing/saving, and the order
 * is added to the Order history and the database.
 *
 * TOPICS HERE: ListView, ImageView + "Reset Image", Spinner (1-10),
 *              event handling from code (Button.setOnAction, TextField + Enter key),
 *              TableView (recipe + cart), Alerts, Initializable, colour-coded categories.
 */
public class OrderController implements Initializable {

    // ---- injected from OrderView.fxml
    @FXML private HBox quickBar;              // filled with a TextField + Buttons created in CODE
    @FXML private HBox actionBar;             // the "Add to Cart" Button is created in CODE and added here
    @FXML private Label todaysDiscountLabel;
    @FXML private ListView<Dish> dishList;
    @FXML private ListView<OrderRecord> historyList;
    @FXML private StackPane dishImageFrame;
    @FXML private ImageView dishImage;
    @FXML private Label selectedDishLabel;
    @FXML private Label orderSummaryLabel;
    @FXML private Label stockAlertLabel;
    @FXML private Label revenueLabel;
    @FXML private Spinner<Integer> qtySpinner;
    @FXML private TableView<RecipeLine> recipeTable;
    @FXML private TableColumn<RecipeLine, String> recIngredientCol;
    @FXML private TableColumn<RecipeLine, String> recNeededCol;
    @FXML private TableColumn<RecipeLine, String> recInStockCol;
    @FXML private TableColumn<RecipeLine, String> recStatusCol;

    // ---- cart (multi-item order by name)
    @FXML private TextField customerNameField;
    @FXML private TextField addToCartField;
    @FXML private TableView<CartLine> cartTable;
    @FXML private TableColumn<CartLine, String> cartDishCol;
    @FXML private TableColumn<CartLine, Number> cartQtyCol;
    @FXML private TableColumn<CartLine, String> cartPriceCol;
    @FXML private TableColumn<CartLine, String> cartLineTotalCol;
    @FXML private Label cartTotalLabel;

    private final InventoryService service = InventoryService.getInstance();
    private final ObservableList<CartLine> cart = FXCollections.observableArrayList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ---------- Spinner: numeric quantity 1..10, start value 1
        qtySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));

        // ---------- today's discount banner
        todaysDiscountLabel.setText("\uD83C\uDF89  " + service.getTodayDiscountText());

        // ---------- ListView of dishes, colour-coded by category, out-of-stock shown in red
        dishList.setItems(service.getDishes());
        dishList.setCellFactory(listView -> new DishCell());
        dishList.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldDish, newDish) -> showDish(newDish));

        historyList.setItems(service.getOrderHistory());
        historyList.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(OrderRecord order, boolean empty) {
                super.updateItem(order, empty);
                setText(empty || order == null ? null : order.toString());
                setWrapText(true);
                setPrefWidth(0);   // wrap inside the list instead of growing a horizontal scrollbar
            }
        });
        historyList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) onViewBill();
        });

        setupRecipeTable();
        setupCartTable();
        buildQuickSearchBar();   // TextField + Enter key, created in code
        buildAddToCartButton();  // Button + setOnAction, created in code

        // Bottom labels always show what is out of stock and today's sales
        stockAlertLabel.textProperty().bind(service.stockAlertProperty());
        revenueLabel.textProperty().bind(Bindings.createStringBinding(
                () -> "Today's sales: " + InventoryService.taka(service.getTodaysRevenue()),
                service.todaysRevenueProperty()));

        // Whenever any stock level changes -> repaint the list, table and title
        service.stockVersionProperty().addListener((observable, oldValue, newValue) -> {
            dishList.refresh();
            refreshSelectedLabel();
        });

        dishList.getSelectionModel().selectFirst();
        refreshCartTotal();
    }

    // ================================================================= DISH LIST CELL (colour-coded)

    /** Colours each row by category and shows the discounted price when today's promo applies. */
    private class DishCell extends ListCell<Dish> {
        @Override
        protected void updateItem(Dish dish, boolean empty) {
            super.updateItem(dish, empty);
            getStyleClass().removeAll(Dish.ALL_CATEGORY_STYLE_CLASSES);
            getStyleClass().remove("out-of-stock-cell");
            if (empty || dish == null) {
                setText(null);
                return;
            }
            getStyleClass().add(Dish.categoryStyleClass(dish.getCategory()));

            String star = dish.isChefSpecial() ? "\u2B50 " : "";
            String priceText;
            if (service.hasDiscountToday(dish)) {
                priceText = String.format("%s -> %s (-%d%%)",
                        InventoryService.taka(dish.getPrice()),
                        InventoryService.taka(service.getDiscountedPrice(dish)),
                        service.getDiscountPercent(dish));
            } else {
                priceText = InventoryService.taka(dish.getPrice());
            }

            if (dish.isAvailable()) {
                setText(String.format("%s%s   [%s]   %s", star, dish.getName(), dish.getCategory(), priceText));
            } else {
                setText(star + dish.getName() + "   [OUT OF STOCK]");
                getStyleClass().add("out-of-stock-cell");
            }
        }
    }

    // ================================================================= EVENT HANDLING FROM CODE

    /** Quick search: a TextField created in Java code that reacts to the ENTER key. */
    private void buildQuickSearchBar() {
        Label title = new Label("Quick find:");
        TextField searchField = new TextField();
        searchField.setPromptText("type part of a dish name and press Enter");
        searchField.setPrefWidth(300);
        Label resultLabel = new Label();

        // EVENT: fires when a key is pressed inside the TextField - we only react to ENTER
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                Dish found = service.findDishByNameLoose(searchField.getText());
                if (found == null) {
                    resultLabel.setText("No dish matches \"" + searchField.getText().trim() + "\".");
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

    /** "Add to Cart" button for the currently selected dish, created in code. */
    private void buildAddToCartButton() {
        Button addSelectedButton = new Button("Add Selected to Cart");
        addSelectedButton.getStyleClass().add("primary-button");
        addSelectedButton.setOnAction(event -> {                 // <-- setOnAction()
            Dish dish = dishList.getSelectionModel().getSelectedItem();
            if (dish == null) {
                AlertUtil.warning("No dish selected", "Please select a dish from the menu first.");
                return;
            }
            addToCart(dish, qtySpinner.getValue());
        });
        actionBar.getChildren().add(addSelectedButton);
    }

    // ================================================================= CART (multi-item order by name)

    private void setupCartTable() {
        cartTable.setItems(cart);

        cartDishCol.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getDish().getName()));
        cartQtyCol.setCellValueFactory(cell -> cell.getValue().quantityProperty());

        cartPriceCol.setCellValueFactory(cell -> {
            Dish dish = cell.getValue().getDish();
            return new SimpleStringProperty(InventoryService.taka(service.getDiscountedPrice(dish)) + " each");
        });

        cartLineTotalCol.setCellValueFactory(cell -> {
            CartLine line = cell.getValue();
            return Bindings.createStringBinding(
                    () -> InventoryService.taka(service.getDiscountedPrice(line.getDish()) * line.getQuantity()),
                    line.quantityProperty());
        });
    }

    /** "Add to Cart" by typed name: looks up the dish the customer typed and adds it to the cart. */
    @FXML
    private void onAddToCart() {
        String typed = addToCartField.getText();
        Dish dish = service.findDishByNameLoose(typed);
        if (dish == null) {
            AlertUtil.warning("Dish not found", "No dish matches \"" + typed.trim()
                    + "\". Try part of a name, e.g. \"sushi\" or \"mango\".");
            return;
        }
        addToCart(dish, qtySpinner.getValue());
        addToCartField.clear();
    }

    /** Adds a dish to the cart, merging with an existing line for the same dish. */
    private void addToCart(Dish dish, int quantity) {
        for (CartLine line : cart) {
            if (line.getDish() == dish) {
                line.addQuantity(quantity);
                refreshCartTotal();
                orderSummaryLabel.setStyle("-fx-text-fill: #1e8449; -fx-font-weight: bold;");
                orderSummaryLabel.setText("Updated cart: " + line.getQuantity() + " x " + dish.getName());
                return;
            }
        }
        cart.add(new CartLine(dish, quantity));
        refreshCartTotal();
        orderSummaryLabel.setStyle("-fx-text-fill: #1e8449; -fx-font-weight: bold;");
        orderSummaryLabel.setText("Added to cart: " + quantity + " x " + dish.getName());
    }

    @FXML
    private void onRemoveCartLine() {
        CartLine selected = cartTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.warning("Nothing selected", "Click a line in the cart table first.");
            return;
        }
        cart.remove(selected);
        refreshCartTotal();
    }

    @FXML
    private void onClearCart() {
        cart.clear();
        refreshCartTotal();
    }

    /**
     * Checkout: (1) the customer's name is required, (2) stock is checked, (3) the payment method
     * is chosen, (4) the order is placed (stock deducted, saved to the order history + database)
     * and (5) the bill paper is shown, ready to print or save.
     */
    @FXML
    private void onPlaceAllOrders() {
        String customer = customerNameField.getText() == null ? "" : customerNameField.getText().trim();
        if (customer.isEmpty()) {
            AlertUtil.warning("Customer name needed", "Please enter the customer's name before placing the order.");
            customerNameField.requestFocus();
            return;
        }

        String problem = service.checkCartProblems(cart);
        if (problem != null) {
            showOrderMessage(problem, false);
            AlertUtil.error("Cannot complete order", problem);
            return;
        }

        String payment = askPaymentMethod(customer, cartTotal());
        if (payment == null) {
            orderSummaryLabel.setStyle("-fx-text-fill: #7f8c8d; -fx-font-weight: bold;");
            orderSummaryLabel.setText("Checkout cancelled - nothing was charged.");
            return;
        }

        InventoryService.OrderResult result = service.placeCartOrder(List.copyOf(cart), customer, payment);
        if (result.success()) {
            showOrderMessage(result.message(), true);
            cart.clear();
            refreshCartTotal();
            customerNameField.clear();
            BillDialog.show(cartTable.getScene().getWindow(), result.order());
        } else {
            showOrderMessage(result.message(), false);
            AlertUtil.error("Cannot complete order", result.message());
        }
    }

    /** Payment step: the customer picks one of several payment methods (radio buttons). */
    private String askPaymentMethod(String customer, double total) {
        ToggleGroup group = new ToggleGroup();
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));
        box.getChildren().add(new Label("Choose a payment method:"));
        for (String method : InventoryService.PAYMENT_METHODS) {
            RadioButton radio = new RadioButton(method);
            radio.setToggleGroup(group);
            radio.setUserData(method);
            if ("Cash".equals(method)) radio.setSelected(true);
            box.getChildren().add(radio);
        }

        ButtonType confirm = new ButtonType("Confirm payment", ButtonBar.ButtonData.OK_DONE);
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Payment");
        dialog.setHeaderText("Customer: " + customer + "\nAmount to pay: " + InventoryService.taka(total));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == confirm && group.getSelectedToggle() != null
                ? (String) group.getSelectedToggle().getUserData() : null);
        return dialog.showAndWait().orElse(null);
    }

    /** Opens the bill of the order selected in the history list. */
    @FXML
    private void onViewBill() {
        OrderRecord selected = historyList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.warning("Nothing selected", "Click an order in the history list first.");
            return;
        }
        BillDialog.show(historyList.getScene().getWindow(), selected);
    }

    private void showOrderMessage(String message, boolean ok) {
        orderSummaryLabel.setStyle(ok ? "-fx-text-fill: #1e8449; -fx-font-weight: bold;"
                                      : "-fx-text-fill: #c0392b; -fx-font-weight: bold;");
        orderSummaryLabel.setText(message);
    }

    private double cartTotal() {
        return cart.stream()
                .mapToDouble(line -> service.getDiscountedPrice(line.getDish()) * line.getQuantity())
                .sum();
    }

    private void refreshCartTotal() {
        double total = cartTotal();
        int items = cart.stream().mapToInt(CartLine::getQuantity).sum();
        cartTotalLabel.setText(String.format("Cart: %d dish(es), %d item(s)   Total: %s",
                cart.size(), items, InventoryService.taka(total)));
    }

    // ================================================================= SELECTED DISH VIEW

    private void showDish(Dish dish) {
        dishImageFrame.getStyleClass().removeAll(Dish.ALL_CATEGORY_STYLE_CLASSES);
        if (dish == null) {
            dishImage.setImage(null);
            recipeTable.setItems(FXCollections.observableArrayList());
            refreshSelectedLabel();
            return;
        }
        dishImageFrame.getStyleClass().add(Dish.categoryStyleClass(dish.getCategory()));
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
        String star = dish.isChefSpecial() ? "\u2B50 Chef's Special   " : "";
        String priceText = service.hasDiscountToday(dish)
                ? String.format("%s  ->  %s  (-%d%% today)", InventoryService.taka(dish.getPrice()),
                        InventoryService.taka(service.getDiscountedPrice(dish)), service.getDiscountPercent(dish))
                : InventoryService.taka(dish.getPrice());
        selectedDishLabel.setText(String.format("%s%s   [%s]   %s%s",
                star, dish.getName(), dish.getCategory(), priceText,
                dish.isAvailable() ? "" : "   [OUT OF STOCK]"));
    }

    // ================================================================= IMAGEVIEW

    /**
     * "Reset Image" button: restores this dish's own correct picture.
     * (Each dish now has its own uniquely generated, labelled image - there is nothing to
     * "change" it to that would still be correct, so this button undoes any accidental
     * mismatch rather than cycling through every other dish's photo.)
     */
    @FXML
    private void onChangeImage() {
        Dish dish = dishList.getSelectionModel().getSelectedItem();
        if (dish == null) {
            AlertUtil.warning("No dish selected", "Select a dish first.");
            return;
        }
        dish.setImageName(dish.getDefaultImageName());
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
