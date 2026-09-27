package com.restaurant.inventory.model;

/**
 * ADVANCED OOP - INTERFACE.
 * Anything that can describe itself in one human-readable line for the
 * "Reports" feature (Kitchen Tools tab). Implemented by unrelated classes
 * (Dish, Ingredient, Person) so they can all be treated the same way
 * (polymorphism) when a report is generated.
 */
public interface Reportable {

    /** One-line, human-readable summary of this object, used by report/export features. */
    String getSummary();
}
