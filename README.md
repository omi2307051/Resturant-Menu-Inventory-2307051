# Restaurant Menu Inventory (JavaFX)

Connects **menu items to ingredient stock**.
When a dish is ordered, the ingredients it needs are **deducted from inventory**, and anything that
is **out of stock** is shown (red in lists/tables, in the bottom status bar, and in alert dialogs).

## Run it

Requirements: **JDK 17+** and **Maven** (JavaFX is downloaded automatically by Maven).

```
mvn clean javafx:run
```

IntelliJ IDEA: *File > Open* the project folder (it detects `pom.xml`), then open the Maven panel
and run `Plugins > javafx > javafx:run`.

## Project structure

```
src/main/java/com/restaurant/inventory
  Main.java                      entry point
  model/    Person, Ingredient, Dish, RecipeLine, MenuItem (abstract), Reportable (interface)
  service/  InventoryService     all data + the "order -> deduct stock" logic + thread pool
  controller/  Main / Order / Inventory / Menu / Staff / Tools controllers
  util/     AlertUtil, DatabaseManager (SQLite), NetworkUtil (HTTP + JSON)
src/main/resources
  fxml/     MainView + one FXML per tab
  css/      styles.css
  images/   sample dish pictures used by ImageView
sample-stock.csv                 try it with File > Open...
restaurant_inventory.db          created automatically on first run (SQLite database file)
```

## Assignment topic coverage

| Topic | Where |
|---|---|
| **Version control** | `git log` - see the commit history. *(Note: this project's Git history only goes back a few days before submission, not to the idea-submission date - commit regularly from now on to fix this for future assignments.)* |
| **Advanced OOP** (interfaces, abstract classes) | `model/Reportable.java` (interface, implemented by `Dish`, `Ingredient`, `Person`) and `model/MenuItem.java` (abstract class, parent of `Dish`) |
| **JavaFX UI design** | see the table below - BorderPane/VBox/HBox/StackPane/FlowPane + most standard controls |
| **Layout responsiveness** | `MainController.setupResponsiveLayout()` - font size and label width react to `Scene.widthProperty()`/`heightProperty()` |
| **Concurrency** | `InventoryService.getExecutor()` (a `ExecutorService` thread pool) used by: "Check supplier price" (Inventory tab), "Fetch live rate" (Kitchen Tools), and File > Open's background CSV parsing - all via `javafx.concurrent.Task` |
| **Database integration (SQLite)** | `util/DatabaseManager.java` - tables `ingredients`, `staff`, `orders` in `restaurant_inventory.db` |
| **CRUD** | Ingredients: Create/Read (seed + File>Open), Update (Restock), Delete (Delete ingredient). Staff: Create (Submit), Read (table), Update (Promote skill), Delete (Remove selected) - all backed by SQLite |
| **Networking & data parsing** | `util/NetworkUtil.java` - HTTP GET with `java.net.http.HttpClient`, JSON parsed with `org.json` ("Fetch live rate" button, Kitchen Tools tab) |

## Where each topic is used

| Topic | Where |
|---|---|
| TableView + ObservableList + `Person` model | **Staff** tab (table of `Person`), **Inventory** tab (table of `Ingredient`), recipe table on **Orders** |
| ImageView + "Change Image" (resource directory) | **Orders** tab - `OrderController.onChangeImage()` cycles through `resources/images` |
| Event handling from code: `setOnAction` | **Orders** tab - "Place Order" and "Clear" buttons are created in `OrderController` |
| TextField + Enter key event | **Orders** tab - "Quick find" box (`setOnKeyPressed`, `KeyCode.ENTER`) |
| Initializable | every controller; e.g. `MainController.initialize()` sets the welcome Label |
| RadioButton (gender) + Label | **Staff** tab |
| ToggleGroup (Beginner / Intermediate / Expert) | **Staff** tab (`<fx:define>` in the FXML) |
| CheckBox (3 hobbies) + Submit | **Staff** tab - hobbies are shown when Submit is clicked |
| ChoiceBox (Red / Green / Blue) | **Kitchen Tools** - changes the window background |
| ComboBox (countries) | **Staff** tab |
| DatePicker + DateTimeFormatter | **Staff** tab - date of birth shown in a Label |
| ColorPicker | **Kitchen Tools** - text colour of the specials-board Label |
| ListView (fruits) | **Kitchen Tools** - "Fruit of the day"; also the dish list on **Orders** |
| TreeView | **Menu** tab - categories > dishes, selected node shown in a Label |
| ProgressBar + "Accumulate" | **Inventory** tab (right side) |
| Slider | **Kitchen Tools** - font size of the specials-board Label |
| Spinner (1-10) | **Orders** tab - quantity |
| MenuBar (File > New, Open, Exit) | top of the window; Exit calls `Platform.exit()` |
| Alert dialogs (Information / Warning / Error) | **Kitchen Tools** buttons, and used everywhere for validation and out-of-stock messages |
| TextArea + Clear | **Kitchen Tools** - kitchen notes |
| PasswordField + show as plain text | **Staff** tab - Show / Hide button |
| FileChooser "Browse" -> ImageView | **Staff** tab (photo); also File > Open loads a CSV |

## Try this demo flow

1. **Orders** > select *Special House Burger*, quantity 3 > **Place Order**. Beef Patty hits 0, a warning
   appears, the dish turns red and the status bar lists what is out of stock.
2. Set quantity 1 and order again - an error alert says which ingredient is short.
3. Order *Beef Steak* x6: Beef Steak Cut drops low/out, so other beef dishes that share it
   (Adana Kebab, Beef Nihari, Special House Pizza, Lasagna) become unavailable too.
4. **Inventory** > click *Beef Patty*, type `10` > **Restock**. The Special House Burger is available again.
5. **Inventory** > type `5` in the Accumulate box and press the button 5 times: the bar fills up.
6. **File > New** resets the day; **File > Open...** with `sample-stock.csv` loads new stock levels.

Notes: the ProgressBar rule is implemented literally - each press adds the typed number N to the sum,
and the bar is full after N presses. Passwords in the Staff form are only validated (min 4 characters)
and never stored - this is a learning demo.
