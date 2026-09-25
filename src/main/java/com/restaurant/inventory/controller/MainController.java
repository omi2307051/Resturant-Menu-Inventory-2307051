package com.restaurant.inventory.controller;

import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Controller of the main window (MenuBar + TabPane + status bar).
 *
 * TOPICS HERE:  Initializable interface, MenuBar (File -> New / Open / Exit).
 */
public class MainController implements Initializable {

    @FXML private Label welcomeLabel;
    @FXML private Label statusLabel;

    private final InventoryService service = InventoryService.getInstance();

    /**
     * INITIALIZABLE: this method runs automatically right after the FXML is loaded,
     * so it is the place to set default values for controls (here: the welcome Label).
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy"));
        welcomeLabel.setText("Restaurant Menu Inventory  -  " + today);

        // The status bar always shows what is out of stock (updates by itself).
        statusLabel.textProperty().bind(service.stockAlertProperty());
    }

    // ---------------------------------------------------------------- MenuBar actions

    /** File -> New : start a fresh day (stock back to default, history cleared). */
    @FXML
    private void onNew() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset all ingredient stock to the default levels and clear the order history?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("New day");
        confirm.setHeaderText("Start a new day?");
        confirm.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> service.resetToDefaults());
    }

    /** File -> Open : load stock levels from a CSV file (see sample-stock.csv). */
    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open stock file (name,quantity,unit)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV / Text files", "*.csv", "*.txt"));

        File file = chooser.showOpenDialog(welcomeLabel.getScene().getWindow());
        if (file == null) {
            return; // user cancelled
        }
        try {
            int count = service.loadStockFromCsv(file);
            AlertUtil.info("Stock loaded", count + " ingredient(s) loaded from " + file.getName());
        } catch (IOException e) {
            AlertUtil.error("Could not read file", e.getMessage());
        }
    }

    /** File -> Exit : close the application. */
    @FXML
    private void onExit() {
        Platform.exit();
    }
}
