package com.restaurant.inventory.controller;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.ParallelTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Controller for the front / splash page (WelcomeView.fxml) shown when the
 * application starts, before the tabbed Orders/Inventory/Menu/Staff window.
 *
 * Main.java supplies a callback (via {@link #setOnEnter}) that swaps the
 * scene's root over to MainView.fxml once the user clicks "Enter Restaurant".
 */
public class WelcomeController {

    @FXML private Button enterButton;
    @FXML private VBox cardBox;
    @FXML private Circle glowCircle;

    private Runnable onEnter;

    @FXML
    private void initialize() {
        // gentle fade + scale-in entrance for the medallion/name card, so the
        // front page feels alive rather than just appearing instantly.
        cardBox.setOpacity(0);
        cardBox.setScaleX(0.92);
        cardBox.setScaleY(0.92);

        FadeTransition fade = new FadeTransition(Duration.millis(900), cardBox);
        fade.setFromValue(0);
        fade.setToValue(1);

        ScaleTransition scale = new ScaleTransition(Duration.millis(900), cardBox);
        scale.setFromX(0.92);
        scale.setFromY(0.92);
        scale.setToX(1);
        scale.setToY(1);

        new ParallelTransition(fade, scale).play();

        // the ambient glow behind the medallion breathes slowly, for a subtle
        // "candlelit dining room" feel rather than a static flat background.
        FadeTransition glowPulse = new FadeTransition(Duration.seconds(3.2), glowCircle);
        glowPulse.setFromValue(0.55);
        glowPulse.setToValue(0.9);
        glowPulse.setCycleCount(FadeTransition.INDEFINITE);
        glowPulse.setAutoReverse(true);
        glowPulse.play();
    }

    /** Called by Main.java right after loading this controller. */
    public void setOnEnter(Runnable onEnter) {
        this.onEnter = onEnter;
    }

    @FXML
    private void onEnterClicked() {
        if (onEnter != null) {
            onEnter.run();
        }
    }
}
