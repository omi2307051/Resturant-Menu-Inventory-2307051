package com.restaurant.inventory.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * MODEL: one line of a customer's cart - a Dish plus how many servings.
 * Used by the Orders tab so a customer can order several different dishes
 * (each found by typing its name) in a single checkout.
 */
public class CartLine {

    private final Dish dish;
    private final IntegerProperty quantity = new SimpleIntegerProperty();

    public CartLine(Dish dish, int quantity) {
        this.dish = dish;
        this.quantity.set(Math.max(1, quantity));
    }

    public Dish getDish() { return dish; }

    public int getQuantity() { return quantity.get(); }
    public void setQuantity(int value) { quantity.set(Math.max(1, value)); }
    public IntegerProperty quantityProperty() { return quantity; }

    public void addQuantity(int amount) { setQuantity(getQuantity() + amount); }

    @Override
    public String toString() { return getQuantity() + " x " + dish.getName(); }
}
