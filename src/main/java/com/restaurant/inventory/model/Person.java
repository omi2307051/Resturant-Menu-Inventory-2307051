package com.restaurant.inventory.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDate;

/**
 * MODEL: Person  (used by the TableView in the "Staff" tab).
 * Represents a staff member of the restaurant.
 * JavaFX properties are used so the TableView can observe changes.
 *
 * ADVANCED OOP: implements the {@link Reportable} interface (see Dish, Ingredient)
 * so it can be listed, polymorphically, in the Reports feature.
 *
 * DATABASE INTEGRATION: {@code id} mirrors the primary key of the "staff" row in
 * SQLite once this person has been saved (see {@link com.restaurant.inventory.util.DatabaseManager}).
 * It is -1 for a Person that has not been persisted yet.
 */
public class Person implements Reportable {

    private final IntegerProperty id = new SimpleIntegerProperty(-1);
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty gender = new SimpleStringProperty();
    private final StringProperty skillLevel = new SimpleStringProperty();
    private final StringProperty country = new SimpleStringProperty();
    private final ObjectProperty<LocalDate> dateOfBirth = new SimpleObjectProperty<>();
    private final StringProperty hobbies = new SimpleStringProperty();
    private final StringProperty photoFile = new SimpleStringProperty();

    public Person(String name, String gender, String skillLevel, String country,
                  LocalDate dateOfBirth, String hobbies, String photoFile) {
        this.name.set(name);
        this.gender.set(gender);
        this.skillLevel.set(skillLevel);
        this.country.set(country);
        this.dateOfBirth.set(dateOfBirth);
        this.hobbies.set(hobbies);
        this.photoFile.set(photoFile);
    }

    // ----- database row id (-1 = not saved yet) -----
    public int getId() { return id.get(); }
    public void setId(int value) { id.set(value); }
    public IntegerProperty idProperty() { return id; }

    // ----- name -----
    public String getName() { return name.get(); }
    public void setName(String value) { name.set(value); }
    public StringProperty nameProperty() { return name; }

    // ----- gender -----
    public String getGender() { return gender.get(); }
    public void setGender(String value) { gender.set(value); }
    public StringProperty genderProperty() { return gender; }

    // ----- skill level -----
    public String getSkillLevel() { return skillLevel.get(); }
    public void setSkillLevel(String value) { skillLevel.set(value); }
    public StringProperty skillLevelProperty() { return skillLevel; }

    // ----- country -----
    public String getCountry() { return country.get(); }
    public void setCountry(String value) { country.set(value); }
    public StringProperty countryProperty() { return country; }

    // ----- date of birth -----
    public LocalDate getDateOfBirth() { return dateOfBirth.get(); }
    public void setDateOfBirth(LocalDate value) { dateOfBirth.set(value); }
    public ObjectProperty<LocalDate> dateOfBirthProperty() { return dateOfBirth; }

    // ----- hobbies -----
    public String getHobbies() { return hobbies.get(); }
    public void setHobbies(String value) { hobbies.set(value); }
    public StringProperty hobbiesProperty() { return hobbies; }

    // ----- photo file name -----
    public String getPhotoFile() { return photoFile.get(); }
    public void setPhotoFile(String value) { photoFile.set(value); }
    public StringProperty photoFileProperty() { return photoFile; }

    @Override
    public String toString() { return getName(); }

    /** INTERFACE IMPLEMENTATION (Reportable): one-line summary used by the Reports feature. */
    @Override
    public String getSummary() {
        return String.format("[Staff] %-20s %-8s %-12s %s", getName(), getGender(),
                getSkillLevel(), getCountry());
    }
}
