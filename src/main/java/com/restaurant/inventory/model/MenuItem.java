package com.restaurant.inventory.model;

/**
 * ADVANCED OOP - ABSTRACT CLASS.
 * Common base for anything that can appear on the menu and be sold.
 * Holds the fields every menu item needs (name, category, price) and
 * forces every concrete subclass to define how it labels itself for
 * display (abstract method - each subclass MUST implement it).
 *
 * {@link Dish} is currently the only subclass, but the class is written
 * so that other kinds of sellable items (e.g. combo meals, add-ons) could
 * extend it later without duplicating the name/category/price plumbing.
 */
public abstract class MenuItem {

    private final String name;
    private final String category;
    private final double price;

    protected MenuItem(String name, String category, double price) {
        this.name = name;
        this.category = category;
        this.price = price;
    }

    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }

    /**
     * Abstract method: every concrete menu item must say how it should be
     * labelled on screen (e.g. a Dish adds a star for Chef's Specials).
     * This is overridden differently by each subclass -> runtime polymorphism.
     */
    public abstract String getDisplayLabel();

    @Override
    public String toString() { return name; }
}
