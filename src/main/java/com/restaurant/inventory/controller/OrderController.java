package com.restaurant.inventory.controller;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.time.LocalDate;

public class OrderController {

    @FXML private TextField customerNameField;
    @FXML private ComboBox<String> itemSelectorComboBox;
    @FXML private Spinner<Integer> quantitySpinner;
    @FXML private ListView<String> orderSummaryListView;
    @FXML private Label totalAmountLabel;
    @FXML private Label discountNoticeLabel;

    private final ObservableList<String> currentOrderItems = FXCollections.observableArrayList();
    private double subtotal = 0.0;

    @FXML
    public void initialize() {
        itemSelectorComboBox.getItems().addAll(
                "Classic Beef Burger (৳350)",
                "Cheesy Chicken Burger (৳320)",
                "Italian Pepperoni Pizza (৳750)",
                "Creamy White Pasta (৳480)",
                "Hot & Sour Thai Soup (৳250)",
                "Chocolate Lava Cake (৳280)",
                "Fresh Mango Juice (৳180)"
        );

        quantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, 1));
        applyDaySpecificDiscount();
    }

    private double getDiscountPercentage() {
        LocalDate today = LocalDate.now();
        switch (today.getDayOfWeek()) {
            case FRIDAY:
            case SATURDAY:
                return 0.15; // 15% weekend discount
            case THURSDAY:
                return 0.10; // 10% Thursday evening special
            default:
                return 0.05; // 5% standard daily member discount
        }
    }

    private void applyDaySpecificDiscount() {
        double discount = getDiscountPercentage() * 100;
        discountNoticeLabel.setText(LocalDate.now().getDayOfWeek() + " Special Promo: " + (int)discount + "% OFF on all orders!");
    }

    @FXML
    private void handleAddMoreItem() {
        String selectedItem = itemSelectorComboBox.getValue();
        int qty = quantitySpinner.getValue();

        if (selectedItem == null) return;

        // Parse price from string bracket pattern
        double unitPrice = extractPrice(selectedItem);
        double itemTotal = unitPrice * qty;
        subtotal += itemTotal;

        currentOrderItems.add(selectedItem + " x " + qty + " = ৳" + String.format("%.2f", itemTotal));
        orderSummaryListView.setItems(currentOrderItems);
        updateTotals();
    }

    private double extractPrice(String itemString) {
        try {
            String pricePart = itemString.substring(itemString.indexOf("৳") + 1, itemString.indexOf(")"));
            return Double.parseDouble(pricePart);
        } catch (Exception e) {
            return 300.0; // Fallback
        }
    }

    private void updateTotals() {
        double discountRate = getDiscountPercentage();
        double discountAmount = subtotal * discountRate;
        double finalTotal = subtotal - discountAmount;

        totalAmountLabel.setText(String.format("Subtotal: ৳%.2f | Discount: -৳%.2f | Final: ৳%.2f",
                subtotal, discountAmount, finalTotal));
    }

    @FXML
    private void handleCheckoutOrder() {
        if (currentOrderItems.isEmpty()) {
            showAlert("Error", "Please add at least one item to the order.");
            return;
        }

        showAlert("Order Success", "Order placed successfully for " + customerNameField.getText() + "!\nReceipt and kitchen ticket generated.");
        currentOrderItems.clear();
        subtotal = 0.0;
        updateTotals();
        customerNameField.clear();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}