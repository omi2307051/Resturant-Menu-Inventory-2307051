package com.restaurant.inventory.model;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * MODEL: an ingredient kept in the kitchen stock (e.g. "Beef Patty", 20 pcs).
 */
public class Ingredient {

    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty unit = new SimpleStringProperty();
    private final DoubleProperty quantity = new SimpleDoubleProperty();
    private final DoubleProperty minLevel = new SimpleDoubleProperty();
    private final double defaultQuantity;

    /** "OK", "LOW" or "OUT OF STOCK" - recalculated automatically when quantity changes. */
    private final StringBinding status;

    public Ingredient(String name, String unit, double quantity, double minLevel) {
        this.name.set(name);
        this.unit.set(unit);
        this.quantity.set(quantity);
        this.minLevel.set(minLevel);
        this.defaultQuantity = quantity;

        this.status = Bindings.createStringBinding(() -> {
            double q = getQuantity();
            if (q <= 0) return "OUT OF STOCK";
            if (q <= getMinLevel()) return "LOW";
            return "OK";
        }, this.quantity, this.minLevel);
    }

    public String getName() { return name.get(); }
    public StringProperty nameProperty() { return name; }

    public String getUnit() { return unit.get(); }
    public StringProperty unitProperty() { return unit; }

    public double getQuantity() { return quantity.get(); }
    public void setQuantity(double value) { quantity.set(Math.max(0, value)); }
    public DoubleProperty quantityProperty() { return quantity; }

    public double getMinLevel() { return minLevel.get(); }
    public DoubleProperty minLevelProperty() { return minLevel; }

    public double getDefaultQuantity() { return defaultQuantity; }

    public String getStatus() { return status.get(); }
    public StringBinding statusProperty() { return status; }

    public boolean isOutOfStock() { return getQuantity() <= 0; }

    /** Removes stock (never goes below zero). */
    public void deduct(double amount) { setQuantity(getQuantity() - amount); }

    /** Adds stock (restock). */
    public void add(double amount) { setQuantity(getQuantity() + amount); }

    /** 20.0 -> "20", 2.5 -> "2.5" */
    public static String formatAmount(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }

    @Override
    public String toString() { return getName(); }
}
