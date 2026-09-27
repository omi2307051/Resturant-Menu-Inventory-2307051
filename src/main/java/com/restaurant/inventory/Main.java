package com.restaurant.inventory;

import com.restaurant.inventory.controller.WelcomeController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Application entry point.
 *
 * Shows the WelcomeView.fxml front page (restaurant name, logo, tagline)
 * first; clicking "Enter Restaurant" swaps the same Scene's root over to
 * MainView.fxml (MenuBar + tabbed Orders/Inventory/Menu/Staff/Tools window).
 */
public class Main extends Application {

    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        showWelcomeScreen();
    }

    /** Front page: restaurant name + logo + tagline + "Enter Restaurant" button. */
    private void showWelcomeScreen() throws IOException {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/fxml/WelcomeView.fxml"));
        Parent root = loader.load();

        WelcomeController controller = loader.getController();
        controller.setOnEnter(this::showMainScreen);

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(Main.class.getResource("/css/styles.css").toExternalForm());

        stage.setTitle("Pavillion 22 Restaurant");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.show();
    }

    /** Main app window: MenuBar + TabPane (Orders is the first tab). */
    private void showMainScreen() {
        try {
            FXMLLoader loader = new FXMLLoader(Main.class.getResource("/fxml/MainView.fxml"));
            Parent root = loader.load();
            stage.getScene().setRoot(root);
            stage.setTitle("Welcome to Pavillion 22 Restaurant");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
