package com.restaurant.inventory.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.LocalDate;

/**
 * MODEL: Person  (used by the TableView in the "Staff" tab).
 * Represents a staff member of the restaurant.
 * JavaFX properties are used so the TableView can observe changes.
 */
public class Person {

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
}
