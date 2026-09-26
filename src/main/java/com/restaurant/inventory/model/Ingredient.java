package com.restaurant.inventory.model;

import javafx.beans.property.*;

public class Ingredient {
    private final StringProperty name;
    private final IntegerProperty quantity;
    private final StringProperty unit;

    public Ingredient(String name, int quantity, String unit) {
        this.name = new SimpleStringProperty(name);
        this.quantity = new SimpleIntegerProperty(quantity);
        this.unit = new SimpleStringProperty(unit);
    }

    public String getName() {
        return name.get();
    }

    public StringProperty nameProperty() {
        return name;
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public int getQuantity() {
        return quantity.get();
    }

    public IntegerProperty quantityProperty() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity.set(quantity);
    }

    public String getUnit() {
        return unit.get();
    }

    public StringProperty unitProperty() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit.set(unit);
    }

    /**
     * Returns the default quantity for this ingredient.
     */
    public int getDefaultQuantity() {
        return getQuantity();
    }

    /**
     * Deducts the specified amount from the ingredient's stock quantity.
     */
    public void deduct(double amount) {
        int current = quantity.get();
        quantity.set((int) Math.max(0, current - amount));
    }

    public void deduct(int amount) {
        int current = quantity.get();
        quantity.set(Math.max(0, current - amount));
    }
}