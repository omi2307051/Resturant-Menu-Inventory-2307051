package com.restaurant.inventory.model;

import javafx.beans.property.*;
import java.util.ArrayList;
import java.util.List;

public class Dish {
    private final StringProperty name;
    private final StringProperty category;
    private final DoubleProperty price;
    private final StringProperty imagePath;
    private final List<RecipeLine> recipe;

    public Dish(String name, String category, double price, String imagePath) {
        this.name = new SimpleStringProperty(name);
        this.category = new SimpleStringProperty(category);
        this.price = new SimpleDoubleProperty(price);
        this.imagePath = new SimpleStringProperty(imagePath);
        this.recipe = new ArrayList<>();
    }

    public Dish(String name, String category, double price, String imagePath, List<RecipeLine> recipe) {
        this.name = new SimpleStringProperty(name);
        this.category = new SimpleStringProperty(category);
        this.price = new SimpleDoubleProperty(price);
        this.imagePath = new SimpleStringProperty(imagePath);
        this.recipe = recipe != null ? recipe : new ArrayList<>();
    }

    public String getName() { return name.get(); }
    public StringProperty nameProperty() { return name; }

    public String getCategory() { return category.get(); }
    public StringProperty categoryProperty() { return category; }

    public double getPrice() { return price.get(); }
    public DoubleProperty priceProperty() { return price; }

    public String getImagePath() { return imagePath.get(); }
    public StringProperty imagePathProperty() { return imagePath; }

    public List<RecipeLine> getRecipe() {
        return recipe;
    }

    public List<String> missingIngredients(int quantity) {
        List<String> missing = new ArrayList<>();
        // Checks recipe ingredients against stock if integrated
        return missing;
    }
}