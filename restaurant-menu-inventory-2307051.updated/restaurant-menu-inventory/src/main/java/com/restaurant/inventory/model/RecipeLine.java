package com.restaurant.inventory.model;

/**
 * One line of a recipe: "this dish needs X amount of this ingredient per serving".
 * This is the link between a menu item and the ingredient stock.
 */
public class RecipeLine {

    private final Ingredient ingredient;
    private final double amount;

    public RecipeLine(Ingredient ingredient, double amount) {
        this.ingredient = ingredient;
        this.amount = amount;
    }

    public Ingredient getIngredient() { return ingredient; }

    /** Amount needed for ONE serving. */
    public double getAmount() { return amount; }
}
