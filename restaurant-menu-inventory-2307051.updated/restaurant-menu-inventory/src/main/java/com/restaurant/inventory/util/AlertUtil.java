package com.restaurant.inventory.util;

import javafx.scene.control.Alert;
import javafx.scene.layout.Region;

/** Small helper so every controller can show dialogs in one line. */
public final class AlertUtil {

    private AlertUtil() { }

    public static void show(Alert.AlertType type, String title, String header, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE); // avoids cut-off long text
        alert.showAndWait();
    }

    public static void info(String title, String message) {
        show(Alert.AlertType.INFORMATION, title, null, message);
    }

    public static void warning(String title, String message) {
        show(Alert.AlertType.WARNING, title, null, message);
    }

    public static void error(String title, String message) {
        show(Alert.AlertType.ERROR, title, null, message);
    }
}
