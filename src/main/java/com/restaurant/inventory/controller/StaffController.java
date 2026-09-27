package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Person;
import com.restaurant.inventory.service.InventoryService;
import com.restaurant.inventory.util.AlertUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * "Staff" tab: a registration form + a table of staff members (Person model).
 *
 * TOPICS HERE: TableView + ObservableList + Person model, RadioButton (gender),
 *              ToggleGroup (skill level), CheckBox (hobbies) + Submit, ComboBox (country),
 *              DatePicker + DateTimeFormatter, PasswordField (show/hide), FileChooser + ImageView.
 *              DATABASE INTEGRATION: Submit/Remove/Promote are full CRUD against the "staff"
 *              table in SQLite (Create/Read/Update/Delete) via InventoryService.
 */
public class StaffController implements Initializable {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMMM yyyy");

    // ---- form controls
    @FXML private TextField nameField;
    @FXML private ToggleGroup genderGroup;          // defined in FXML with <fx:define>
    @FXML private ToggleGroup skillGroup;           // defined in FXML with <fx:define>
    @FXML private ToggleGroup roleGroup;            // defined in FXML with <fx:define>
    @FXML private RadioButton beginnerRadio;
    @FXML private Label genderLabel;
    @FXML private Label roleLabel;
    @FXML private ComboBox<String> countryCombo;
    @FXML private DatePicker dobPicker;
    @FXML private Label dobLabel;
    @FXML private CheckBox readingCheck;
    @FXML private CheckBox gamingCheck;
    @FXML private CheckBox travelingCheck;
    @FXML private Label hobbiesLabel;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordPlainField;
    @FXML private Button showPasswordButton;
    @FXML private ImageView photoView;
    @FXML private Label photoNameLabel;
    @FXML private Label formMessageLabel;

    // ---- table
    @FXML private TableView<Person> staffTable;
    @FXML private TableColumn<Person, String> nameCol;
    @FXML private TableColumn<Person, String> roleCol;
    @FXML private TableColumn<Person, String> genderCol;
    @FXML private TableColumn<Person, String> skillCol;
    @FXML private TableColumn<Person, String> countryCol;
    @FXML private TableColumn<Person, String> dobCol;
    @FXML private TableColumn<Person, String> hobbiesCol;
    @FXML private TableColumn<Person, String> photoCol;

    private final InventoryService service = InventoryService.getInstance();
    private String selectedPhotoUri;     // set by the Browse button
    private String selectedPhotoName;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupTable();
        setupGender();
        setupRole();
        setupSkillLevel();
        setupCountries();
        setupDatePicker();
        setupPassword();

        hobbiesLabel.setText("Selected hobbies: (press Submit)");
        photoNameLabel.setText("No photo chosen");
    }

    // ================================================================= TABLEVIEW + Person

    private void setupTable() {
        // The TableView shows an ObservableList<Person>: add/remove a Person and the table updates.
        staffTable.setItems(service.getStaff());

        nameCol.setCellValueFactory(cell -> cell.getValue().nameProperty());
        roleCol.setCellValueFactory(cell -> cell.getValue().roleProperty());
        genderCol.setCellValueFactory(cell -> cell.getValue().genderProperty());
        skillCol.setCellValueFactory(cell -> cell.getValue().skillLevelProperty());
        countryCol.setCellValueFactory(cell -> cell.getValue().countryProperty());
        hobbiesCol.setCellValueFactory(cell -> cell.getValue().hobbiesProperty());
        photoCol.setCellValueFactory(cell -> cell.getValue().photoFileProperty());
        dobCol.setCellValueFactory(cell -> {
            LocalDate date = cell.getValue().getDateOfBirth();
            return new SimpleStringProperty(date == null ? "" : date.format(DATE_FORMAT));
        });

        // ---- THREE independent ways to delete a row, so there is always one that works: ----

        // 1) Right-click a row -> "Delete staff member" context menu.
        staffTable.setRowFactory(tv -> {
            TableRow<Person> row = new TableRow<>();
            MenuItem deleteItem = new MenuItem("Delete staff member");
            deleteItem.setOnAction(e -> deletePerson(row.getItem()));
            ContextMenu menu = new ContextMenu(deleteItem);
            // only show the menu on rows that actually hold a Person (not empty rows)
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));
            return row;
        });

        // 2) Select a row, press the Delete (or Backspace) key.
        staffTable.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deletePerson(staffTable.getSelectionModel().getSelectedItem());
            }
        });
    }

    /** DELETE (database CRUD): removes the selected staff member from the table AND SQLite. */
    @FXML
    private void onRemoveStaff() {
        // 3) The "Delete Selected Staff" button.
        deletePerson(staffTable.getSelectionModel().getSelectedItem());
    }

    /** Shared delete logic used by the button, the right-click menu, and the Delete key. */
    private void deletePerson(Person selected) {
        if (selected == null) {
            AlertUtil.warning("Nothing selected", "Click a row in the table first, then delete it.");
            return;
        }
        boolean confirmed = AlertUtil.confirm("Delete staff member",
                "Delete " + selected.getName() + " (" + selected.getRole() + ")? This cannot be undone.");
        if (!confirmed) {
            return;
        }
        service.removeStaff(selected);
        staffTable.getSelectionModel().clearSelection();
        formMessageLabel.setText("Deleted: " + selected.getName());
    }

    /**
     * UPDATE (database CRUD): cycles the selected staff member's skill level
     * (Beginner -> Intermediate -> Expert -> Beginner ...) and persists the change to SQLite.
     */
    @FXML
    private void onPromoteStaff() {
        Person selected = staffTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.warning("Nothing selected", "Click a row in the table first.");
            return;
        }
        String next = switch (selected.getSkillLevel() == null ? "" : selected.getSkillLevel()) {
            case "Beginner" -> "Intermediate";
            case "Intermediate" -> "Expert";
            default -> "Beginner";
        };
        service.updateStaffSkill(selected, next);
        staffTable.refresh();
    }

    // ================================================================= RADIO BUTTONS

    /** Gender: the 3 RadioButtons share one ToggleGroup (defined in the FXML) -> only one can be selected. */
    private void setupGender() {
        genderLabel.setText("Selected gender: (none)");
        genderGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            if (newToggle == null) {
                genderLabel.setText("Selected gender: (none)");
            } else {
                genderLabel.setText("Selected gender: " + ((RadioButton) newToggle).getText());
            }
        });
    }

    /** Skill level: Beginner / Intermediate / Expert in a second ToggleGroup. */
    private void setupSkillLevel() {
        beginnerRadio.setSelected(true);   // default value
    }

    /** Role: Chef / Server - a third ToggleGroup, required just like gender (no default). */
    private void setupRole() {
        roleLabel.setText("Selected role: (none)");
        roleGroup.selectedToggleProperty().addListener((observable, oldToggle, newToggle) -> {
            if (newToggle == null) {
                roleLabel.setText("Selected role: (none)");
            } else {
                roleLabel.setText("Selected role: " + ((RadioButton) newToggle).getText());
            }
        });
    }

    // ================================================================= COMBOBOX

    private void setupCountries() {
        List<String> countries = Arrays.stream(Locale.getISOCountries())
                .map(code -> new Locale("", code).getDisplayCountry(Locale.ENGLISH))
                .filter(name -> !name.isBlank())
                .sorted()
                .collect(Collectors.toList());
        countryCombo.setItems(FXCollections.observableArrayList(countries));
        countryCombo.setVisibleRowCount(10);
    }

    // ================================================================= DATEPICKER

    private void setupDatePicker() {
        dobLabel.setText("Date of birth: (not selected)");

        // a birthday cannot be in the future -> disable those days
        dobPicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isAfter(LocalDate.now()));
            }
        });

        // show the chosen date in a Label using a DateTimeFormatter
        dobPicker.valueProperty().addListener((observable, oldDate, newDate) ->
                dobLabel.setText(newDate == null
                        ? "Date of birth: (not selected)"
                        : "Date of birth: " + newDate.format(DATE_FORMAT)));
    }

    // ================================================================= PASSWORDFIELD

    /** A PasswordField and a TextField share the same text; the button switches which one is visible. */
    private void setupPassword() {
        passwordPlainField.textProperty().bindBidirectional(passwordField.textProperty());
    }

    @FXML
    private void onTogglePassword() {
        boolean showPlain = !passwordPlainField.isVisible();

        passwordPlainField.setVisible(showPlain);
        passwordPlainField.setManaged(showPlain);
        passwordField.setVisible(!showPlain);
        passwordField.setManaged(!showPlain);

        showPasswordButton.setText(showPlain ? "Hide" : "Show");
    }

    // ================================================================= FILECHOOSER + IMAGEVIEW

    @FXML
    private void onBrowsePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));

        File file = chooser.showOpenDialog(photoView.getScene().getWindow());
        if (file != null) {
            selectedPhotoUri = file.toURI().toString();
            selectedPhotoName = file.getName();
            photoView.setImage(new Image(selectedPhotoUri, 90, 90, true, true));
            photoNameLabel.setText(file.getName());
        }
    }

    // ================================================================= SUBMIT (CheckBoxes)

    @FXML
    private void onSubmit() {
        String name = nameField.getText().trim();
        Toggle gender = genderGroup.getSelectedToggle();
        Toggle skill = skillGroup.getSelectedToggle();
        Toggle role = roleGroup.getSelectedToggle();
        String country = countryCombo.getValue();
        LocalDate dob = dobPicker.getValue();

        // ---- simple validation
        if (name.isEmpty()) {
            AlertUtil.warning("Missing name", "Please type the staff member's name.");
            return;
        }
        if (gender == null) {
            AlertUtil.warning("Missing gender", "Please choose a gender.");
            return;
        }
        if (role == null) {
            AlertUtil.warning("Missing role", "Please choose a role (Chef or Server).");
            return;
        }
        if (country == null) {
            AlertUtil.warning("Missing country", "Please choose a country.");
            return;
        }
        if (dob == null) {
            AlertUtil.warning("Missing date of birth", "Please pick a date of birth.");
            return;
        }
        if (passwordField.getText().length() < 4) {
            AlertUtil.warning("Weak password", "The password must have at least 4 characters.");
            return;
        }

        // ---- CHECKBOXES: collect every ticked hobby and display them
        List<CheckBox> hobbyBoxes = List.of(readingCheck, gamingCheck, travelingCheck);
        String hobbies = hobbyBoxes.stream()
                .filter(CheckBox::isSelected)
                .map(CheckBox::getText)
                .collect(Collectors.joining(", "));
        if (hobbies.isEmpty()) {
            hobbies = "None";
        }
        hobbiesLabel.setText("Selected hobbies: " + hobbies);

        // ---- create the Person model and add it to the ObservableList -> the table updates itself
        Person person = new Person(
                name,
                ((RadioButton) gender).getText(),
                ((RadioButton) skill).getText(),
                country,
                dob,
                hobbies,
                selectedPhotoName == null ? "-" : selectedPhotoName,
                ((RadioButton) role).getText());
        service.addStaff(person);

        formMessageLabel.setText("Added: " + name + " (password is not stored in this demo)");
        clearForm();
    }

    private void clearForm() {
        nameField.clear();
        genderGroup.selectToggle(null);
        roleGroup.selectToggle(null);
        beginnerRadio.setSelected(true);
        countryCombo.setValue(null);
        dobPicker.setValue(null);
        readingCheck.setSelected(false);
        gamingCheck.setSelected(false);
        travelingCheck.setSelected(false);
        passwordField.clear();
        photoView.setImage(null);
        photoNameLabel.setText("No photo chosen");
        selectedPhotoUri = null;
        selectedPhotoName = null;
    }
}
