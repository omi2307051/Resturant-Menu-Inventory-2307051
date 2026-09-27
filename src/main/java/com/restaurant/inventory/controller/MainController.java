package com.restaurant.inventory.controller;

import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Controller of the main window (MenuBar + TabPane + status bar).
 *
 * TOPICS HERE:  Initializable interface, MenuBar (File -> New / Open / Exit),
 *               CONCURRENCY (File > Open... parses the CSV on a background thread so a
 *               large file never freezes the window), LAYOUT RESPONSIVENESS (the welcome
 *               banner's font size and the status bar's wrapping react to the window's
 *               actual width/height through property bindings/listeners, not fixed pixels).
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

        setupResponsiveLayout();
    }

    /**
     * LAYOUT RESPONSIVENESS: once this label is attached to a real Scene, listen to the
     * scene's widthProperty()/heightProperty() (property constraints, not hard-coded pixel
     * values) and rescale the welcome banner's font and the status label's wrap width to
     * match - so the UI keeps looking right whether the user shrinks or maximises the window.
     */
    private void setupResponsiveLayout() {
        welcomeLabel.sceneProperty().addListener((obsScene, oldScene, newScene) -> {
            if (newScene == null) return;
            newScene.widthProperty().addListener((obsW, oldW, newW) ->
                    applyResponsiveSizing(newW.doubleValue(), newScene.getHeight()));
            newScene.heightProperty().addListener((obsH, oldH, newH) ->
                    applyResponsiveSizing(newScene.getWidth(), newH.doubleValue()));
            applyResponsiveSizing(newScene.getWidth(), newScene.getHeight());
        });
    }

    private void applyResponsiveSizing(double sceneWidth, double sceneHeight) {
        // font size scales with width (clamped so it never gets silly-small or silly-large)
        double fontSize = Math.max(16, Math.min(26, sceneWidth / 55.0));
        welcomeLabel.setStyle("-fx-font-size: " + fontSize + "px;");

        // the status bar wraps at ~92% of the current window width instead of a fixed number
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(sceneWidth * 0.92);
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

    /**
     * File -> Open : load stock levels from a CSV file (see sample-stock.csv).
     *
     * CONCURRENCY: reading + parsing the file happens in a {@link Task} on the shared
     * background thread pool ({@link InventoryService#getExecutor()}) so a very large file
     * cannot freeze the JavaFX UI thread. Only the final step - applying the parsed rows to
     * the ObservableList - runs back on the UI thread, inside setOnSucceeded (Task guarantees
     * that callback fires on the FX Application Thread).
     */
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

        Task<List<Object[]>> parseTask = new Task<>() {
            @Override
            protected List<Object[]> call() throws Exception {
                return service.parseStockCsv(file);
            }
        };
        parseTask.setOnSucceeded(event -> {
            int count = service.applyParsedStock(parseTask.getValue());
            AlertUtil.info("Stock loaded", count + " ingredient(s) loaded from " + file.getName());
        });
        parseTask.setOnFailed(event -> AlertUtil.error("Could not read file",
                parseTask.getException() == null ? "Unknown error" : parseTask.getException().getMessage()));

        service.getExecutor().submit(parseTask);
    }

    /** File -> Exit : close the application. */
    @FXML
    private void onExit() {
        Platform.exit();
    }
}
