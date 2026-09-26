package com.restaurant.inventory.controller;

import com.restaurant.inventory.util.AlertUtil;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
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
 *              ListView (fruits), TextArea (+ Clear button), Alert dialogs (Information / Warning / Error).
 */
public class ToolsController implements Initializable {

    @FXML private ChoiceBox<String> themeChoice;
    @FXML private Label specialsLabel;
    @FXML private ColorPicker textColorPicker;
    @FXML private Slider fontSlider;
    @FXML private Label fontSizeLabel;
    @FXML private ListView<String> fruitList;
    @FXML private Label fruitLabel;
    @FXML private TextArea notesArea;

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
}
