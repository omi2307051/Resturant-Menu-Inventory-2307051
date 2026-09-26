package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * "Inventory" tab: the ingredient stock table, restocking, and the Accumulate / ProgressBar demo.
 *
 * TOPICS HERE: TableView + ObservableList (Ingredient model), ProgressBar, TextField, Alerts.
 */
public class InventoryController implements Initializable {

    @FXML private Label stockAlertLabel;
    @FXML private TableView<Ingredient> ingredientTable;
    @FXML private TableColumn<Ingredient, String> nameCol;
    @FXML private TableColumn<Ingredient, Number> qtyCol;
    @FXML private TableColumn<Ingredient, String> unitCol;
    @FXML private TableColumn<Ingredient, Number> minCol;
    @FXML private TableColumn<Ingredient, String> statusCol;

    // restock panel
    @FXML private Label selectedIngredientLabel;
    @FXML private TextField restockField;
    @FXML private Label restockMessageLabel;

    // accumulate / progress panel
    @FXML private TextField targetField;
    @FXML private Label sumLabel;
    @FXML private Label pressesLabel;
    @FXML private ProgressBar progressBar;

    private final InventoryService service = InventoryService.getInstance();

    // state of the Accumulate demo
    private double currentSum = 0;
    private int pressCount = 0;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupTable();

        stockAlertLabel.textProperty().bind(service.stockAlertProperty());

        ingredientTable.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, ingredient) ->
                selectedIngredientLabel.setText(ingredient == null
                        ? "Select an ingredient in the table"
                        : "Selected: " + ingredient.getName() + " (" + ingredient.getUnit() + ")"));

        // extra safety: repaint the table when the list changes
        service.getIngredients().addListener((ListChangeListener<Ingredient>) change -> ingredientTable.refresh());

        // default values for the Accumulate panel
        sumLabel.setText("Current sum: 0");
        pressesLabel.setText("Presses: 0");
        progressBar.setProgress(0);
    }

    // ================================================================= TABLEVIEW

    private void setupTable() {
        // TableView is filled from an ObservableList<Ingredient>
        ingredientTable.setItems(service.getIngredients());

        nameCol.setCellValueFactory(cell -> cell.getValue().nameProperty());
        unitCol.setCellValueFactory(cell -> cell.getValue().unitProperty());
        qtyCol.setCellValueFactory(cell -> cell.getValue().quantityProperty());
        minCol.setCellValueFactory(cell -> cell.getValue().minLevelProperty());
        statusCol.setCellValueFactory(cell -> cell.getValue().statusProperty());

        qtyCol.setCellFactory(column -> numberCell());
        minCol.setCellFactory(column -> numberCell());

        // colour the status: red = out of stock, orange = low, green = ok
        statusCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(status);
                switch (status) {
                    case "OUT OF STOCK" -> setStyle("-fx-text-fill: white; -fx-background-color: #c0392b; -fx-font-weight: bold;");
                    case "LOW"          -> setStyle("-fx-text-fill: #7d5a00; -fx-background-color: #f9e79f; -fx-font-weight: bold;");
                    default             -> setStyle("-fx-text-fill: #1e8449; -fx-font-weight: bold;");
                }
            }
        });
    }

    private TableCell<Ingredient, Number> numberCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : Ingredient.formatAmount(value.doubleValue()));
            }
        };
    }

    // ================================================================= RESTOCK

    @FXML
    private void onRestock() {
        Ingredient ingredient = ingredientTable.getSelectionModel().getSelectedItem();
        if (ingredient == null) {
            AlertUtil.warning("Nothing selected", "Click an ingredient in the table first.");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(restockField.getText().trim());
        } catch (NumberFormatException e) {
            AlertUtil.error("Invalid number", "Please type the amount to add, for example 10 or 250.");
            return;
        }
        if (amount <= 0) {
            AlertUtil.error("Invalid number", "The amount must be greater than 0.");
            return;
        }
        ingredient.add(amount);
        restockMessageLabel.setText("Added " + Ingredient.formatAmount(amount) + " " + ingredient.getUnit()
                + " to " + ingredient.getName() + ".");
        restockField.clear();
    }

    // ================================================================= PROGRESSBAR

    /**
     * ACCUMULATE:
     *  - reads a number N from the TextField
     *  - each press adds N to currentSum (shown in a Label, starts at 0)
     *  - the ProgressBar = presses / N, so it becomes full after exactly N presses
     */
    @FXML
    private void onAccumulate() {
        double number;
        try {
            number = Double.parseDouble(targetField.getText().trim());
        } catch (NumberFormatException e) {
            AlertUtil.error("Invalid number", "Type a whole number in the box first (for example 5).");
            return;
        }
        int target = (int) number;
        if (number != target || target < 1) {
            AlertUtil.error("Invalid number", "Please enter a whole number of 1 or more.");
            return;
        }
        if (pressCount >= target) {
            AlertUtil.info("Already complete", "The progress bar is already full. Press Reset to start again.");
            return;
        }

        pressCount++;
        currentSum += number;

        sumLabel.setText("Current sum: " + Ingredient.formatAmount(currentSum));
        pressesLabel.setText("Presses: " + pressCount + " / " + target);
        progressBar.setProgress((double) pressCount / target);   // reaches 1.0 = fully filled

        if (pressCount == target) {
            AlertUtil.info("Done", "The progress bar is full after " + target + " presses.");
        }
    }

    @FXML
    private void onResetAccumulate() {
        currentSum = 0;
        pressCount = 0;
        sumLabel.setText("Current sum: 0");
        pressesLabel.setText("Presses: 0");
        progressBar.setProgress(0);
    }
}
