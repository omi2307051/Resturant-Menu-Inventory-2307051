package com.restaurant.inventory.controller;

import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * "Kitchen Tools" tab: small helpers for the restaurant team.
 *
 * TOPICS HERE: ChoiceBox (window colour), ColorPicker (text colour), Slider (font size),
 *              ListView (fruits), TextArea (+ Clear button), Alert dialogs (Information / Warning / Error),
 *              NETWORKING &amp; DATA PARSING (live USD -&gt; BDT rate fetched over HTTP and parsed from JSON,
 *              on a background thread so the UI never freezes while waiting on the network),
 *              ADVANCED OOP - POLYMORPHISM (the Reports button lists Dish/Ingredient/Person
 *              through the single Reportable interface).
 */
public class ToolsController implements Initializable {

    private final InventoryService service = InventoryService.getInstance();

    @FXML private ChoiceBox<String> themeChoice;
    @FXML private Label specialsLabel;
    @FXML private ColorPicker textColorPicker;
    @FXML private Slider fontSlider;
    @FXML private Label fontSizeLabel;
    @FXML private ListView<String> fruitList;
    @FXML private Label fruitLabel;
    @FXML private TextArea notesArea;

    // networking + JSON parsing
    @FXML private Button fetchRateButton;
    @FXML private Label exchangeRateLabel;

    // reports (Reportable interface, polymorphism)
    @FXML private ListView<String> reportList;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ---------- ChoiceBox: Red / Green / Blue -> change the window background
        themeChoice.getItems().addAll("Red", "Green", "Blue");
        themeChoice.valueProperty().addListener((observable, oldValue, colorName) -> {
            if (colorName == null) {
                return;
            }
            String css = switch (colorName) {
                case "Red"   -> "#ffd6d6";
                case "Green" -> "#d6f5d6";
                default      -> "#d6e6ff";   // Blue
            };
            Parent root = themeChoice.getScene().getRoot();   // the root pane of the whole window
            root.setStyle("-fx-background-color: " + css + ";");
        });

        // ---------- ColorPicker: start with black to match the Label
        textColorPicker.setValue(Color.BLACK);
        specialsLabel.setTextFill(Color.BLACK);

        // ---------- Slider: change the font size of the Label while dragging
        applyFontSize(fontSlider.getValue());
        fontSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                applyFontSize(newValue.doubleValue()));

        // ---------- ListView of fruits -> selected fruit is shown in a Label
        fruitList.getItems().addAll("Apple", "Banana", "Grapes", "Mango", "Orange", "Pineapple", "Strawberry");
        fruitLabel.setText("Fruit of the day: (none)");
        fruitList.getSelectionModel().selectedItemProperty().addListener((observable, oldFruit, newFruit) -> {
            if (newFruit != null) {
                fruitLabel.setText("Fruit of the day: " + newFruit);
            }
        });
    }

    // ================================================================= COLORPICKER

    @FXML
    private void onColorPicked() {
        specialsLabel.setTextFill(textColorPicker.getValue());
    }

    // ================================================================= SLIDER

    private void applyFontSize(double size) {
        specialsLabel.setFont(Font.font(specialsLabel.getFont().getFamily(), size));
        fontSizeLabel.setText(String.format("Font size: %.0f", size));
    }

    // ================================================================= TEXTAREA

    @FXML
    private void onClearNotes() {
        notesArea.clear();
        notesArea.requestFocus();
    }

    // ================================================================= ALERT DIALOGS

    @FXML
    private void onShowInfo() {
        AlertUtil.info("Information", "Orders are saved automatically while the app is running.");
    }

    @FXML
    private void onShowWarning() {
        AlertUtil.warning("Warning", "Some ingredients are running low. Please check the Inventory tab.");
    }

    @FXML
    private void onShowError() {
        AlertUtil.error("Error", "Something went wrong while saving. Please try again.");
    }

    // ================================================================= NETWORKING + JSON PARSING

    /**
     * Makes a real HTTP GET request (see {@link com.restaurant.inventory.util.NetworkUtil})
     * and parses the JSON response to show today's live USD -> BDT exchange rate.
     * Runs on the shared background thread pool so a slow/unavailable network never freezes
     * the window; the button is disabled while the request is in flight.
     */
    @FXML
    private void onFetchExchangeRate() {
        fetchRateButton.setDisable(true);
        Task<Double> task = service.fetchExchangeRateTask();
        exchangeRateLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            exchangeRateLabel.textProperty().unbind();
            exchangeRateLabel.setText(String.format("1 USD = \u09F3%.2f (live rate)", task.getValue()));
            fetchRateButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            exchangeRateLabel.textProperty().unbind();
            exchangeRateLabel.setText("Could not fetch the live rate (check your internet connection).");
            fetchRateButton.setDisable(false);
        });

        service.getExecutor().submit(task);
    }

    // ================================================================= REPORTS (Reportable interface)

    /**
     * ADVANCED OOP: asks the service for one-line summaries of every Dish, Ingredient and
     * Person - three completely unrelated classes - through the single Reportable interface
     * (polymorphism: the same getSummary() call produces different text for each class).
     */
    @FXML
    private void onGenerateReport() {
        reportList.getItems().setAll(service.generateReportSummaries());
    }
}
