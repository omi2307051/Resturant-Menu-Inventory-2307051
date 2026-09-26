package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Dish;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

public class MenuController {

    @FXML private TilePane menuTilePane;
    @FXML private ComboBox<String> categoryFilter;

    private final ObservableList<Dish> menuList = FXCollections.observableArrayList(
            // Fast Food / Burgers
            new Dish("Classic Beef Burger", "Burger", 350.0, "/images/burger.png"),
            new Dish("Cheesy Chicken Burger", "Burger", 320.0, "/images/burger.png"),
            // Pizza & Italian
            new Dish("Italian Pepperoni Pizza", "Pizza", 750.0, "/images/pizza.png"),
            new Dish("Creamy White Pasta", "Foreign Dish", 480.0, "/images/pasta.png"),
            new Dish("Mexican Lasagna", "Foreign Dish", 550.0, "/images/pasta.png"),
            // Appetizers / Soups & Salads
            new Dish("Hot & Sour Thai Soup", "Appetizer", 250.0, "/images/soup.png"),
            new Dish("Crispy French Fries", "Appetizer", 150.0, "/images/salad.png"),
            new Dish("Fresh Greek Salad", "Salad", 220.0, "/images/salad.png"),
            // Desserts
            new Dish("Chocolate Lava Cake", "Dessert", 280.0, "/images/dessert.png"),
            new Dish("Traditional Faluda", "Dessert", 200.0, "/images/dessert.png"),
            // Beverages
            new Dish("Fresh Mango Juice", "Juice", 180.0, "/images/juice.png")
    );

    @FXML
    public void initialize() {
        categoryFilter.getItems().addAll("All", "Burger", "Pizza", "Foreign Dish", "Appetizer", "Salad", "Dessert", "Juice");
        categoryFilter.setValue("All");
        categoryFilter.setOnAction(e -> filterMenu(categoryFilter.getValue()));

        displayMenu(menuList);
    }

    private void filterMenu(String category) {
        menuTilePane.getChildren().clear();
        if ("All".equals(category)) {
            displayMenu(menuList);
        } else {
            ObservableList<Dish> filtered = FXCollections.observableArrayList();
            for (Dish d : menuList) {
                if (d.getCategory().equalsIgnoreCase(category)) {
                    filtered.add(d);
                }
            }
            displayMenu(filtered);
        }
    }

    private void displayMenu(ObservableList<Dish> dishes) {
        for (Dish dish : dishes) {
            VBox card = new VBox(10);
            card.getStyleClass().add("food-card");
            card.setPrefSize(180, 220);

            ImageView imageView = new ImageView();
            try {
                imageView.setImage(new Image(getClass().getResourceAsStream(dish.getImagePath())));
            } catch (Exception e) {
                // Fallback if image path missing
            }
            imageView.setFitWidth(100);
            imageView.setFitHeight(100);
            imageView.setPreserveRatio(true);

            Label nameLbl = new Label(dish.getName());
            nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #2c3e50;");

            Label catLbl = new Label("Category: " + dish.getCategory());
            catLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #7f8c8d;");

            Label priceLbl = new Label(String.format("৳%.2f", dish.getPrice()));
            priceLbl.getStyleClass().add("currency-label");

            card.getChildren().addAll(imageView, nameLbl, catLbl, priceLbl);
            menuTilePane.getChildren().add(card);
        }
    }
}