package com.restaurant.inventory.controller;

import com.restaurant.inventory.model.Dish;
import com.restaurant.inventory.model.RecipeLine;
import com.restaurant.inventory.model.Ingredient;
import com.restaurant.inventory.service.InventoryService;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * "Menu" tab: the menu organised as a tree (categories -> dishes).
 *
 * TOPIC HERE: TreeView - the selected node's name is shown in a Label.
 */
public class MenuController implements Initializable {

    @FXML private TreeView<String> menuTree;
    @FXML private Label selectedNodeLabel;
    @FXML private Label detailsLabel;

    private final InventoryService service = InventoryService.getInstance();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        buildTree();

        selectedNodeLabel.setText("Selected node: (click something in the tree)");
        detailsLabel.setText("");

        // TreeView selection -> show the node's name in a Label
        menuTree.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldItem, newItem) -> showNode(newItem));

        // keep the availability text up to date when stock changes
        service.stockVersionProperty().addListener((observable, oldValue, newValue) ->
                showNode(menuTree.getSelectionModel().getSelectedItem()));
    }

    /** Root -> categories (Starters, Main Course, ...) -> dishes. */
    private void buildTree() {
        TreeItem<String> rootItem = new TreeItem<>("Restaurant Menu");
        rootItem.setExpanded(true);

        Map<String, TreeItem<String>> categories = new LinkedHashMap<>();
        for (Dish dish : service.getDishes()) {
            TreeItem<String> categoryItem = categories.get(dish.getCategory());
            if (categoryItem == null) {
                categoryItem = new TreeItem<>(dish.getCategory());
                categoryItem.setExpanded(true);
                categories.put(dish.getCategory(), categoryItem);
                rootItem.getChildren().add(categoryItem);
            }
            categoryItem.getChildren().add(new TreeItem<>(dish.getName()));
        }
        menuTree.setRoot(rootItem);
    }

    private void showNode(TreeItem<String> item) {
        if (item == null) {
            return;
        }
        String name = item.getValue();
        selectedNodeLabel.setText("Selected node: " + name);

        Dish dish = service.findDish(name);
        if (dish != null) {
            detailsLabel.setText(describe(dish));
        } else if (item.getParent() == null) {
            detailsLabel.setText("This is the root of the menu.\nIt contains "
                    + item.getChildren().size() + " categories.");
        } else {
            detailsLabel.setText("Category \"" + name + "\" contains "
                    + item.getChildren().size() + " dish(es).");
        }
    }

    private String describe(Dish dish) {
        StringBuilder text = new StringBuilder();
        text.append("Category: ").append(dish.getCategory()).append('\n');
        text.append(String.format("Price: $%.2f%n", dish.getPrice()));

        List<String> missing = dish.missingIngredients(1);
        if (missing.isEmpty()) {
            text.append("Availability: AVAILABLE\n");
        } else {
            text.append("Availability: OUT OF STOCK\n");
            text.append("Missing: ").append(String.join(", ", missing)).append('\n');
        }

        text.append("\nRecipe (per serving):\n");
        for (RecipeLine line : dish.getRecipe()) {
            text.append("  - ").append(line.getIngredient().getName()).append(": ")
                    .append(Ingredient.formatAmount(line.getAmount())).append(' ')
                    .append(line.getIngredient().getUnit()).append('\n');
        }
        return text.toString();
    }
}
