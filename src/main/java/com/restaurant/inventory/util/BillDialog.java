package com.restaurant.inventory.util;

import com.restaurant.inventory.model.OrderRecord;
import javafx.event.ActionEvent;
import javafx.print.PrinterJob;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Shows the "bill paper" of an order in a dialog, with buttons to print it on paper
 * or save it as a .txt file. Used right after checkout and again from the order history.
 */
public final class BillDialog {

    private BillDialog() { }

    public static void show(Window owner, OrderRecord order) {
        String text = order.getBillText();

        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setFont(Font.font("Monospaced", 13));
        area.setPrefColumnCount(BillFormatter.WIDTH + 2);
        area.setPrefRowCount(Math.min(30, text.split("\n").length + 1));

        Dialog<Void> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Bill #" + order.getBillNo());
        dialog.setHeaderText("Bill for " + order.getCustomer() + "  -  paid via " + order.getPaymentMethod());
        dialog.getDialogPane().setContent(area);

        ButtonType printType = new ButtonType("Print", ButtonBar.ButtonData.LEFT);
        ButtonType saveType = new ButtonType("Save as .txt", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(printType, saveType, ButtonType.CLOSE);

        // Print / Save must NOT close the dialog, so consume their action events.
        Button printButton = (Button) dialog.getDialogPane().lookupButton(printType);
        printButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            print(owner, text);
        });
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            save(owner, order);
        });

        dialog.showAndWait();
    }

    private static void print(Window owner, String text) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            AlertUtil.warning("Print", "No printer is available. Use \"Save as .txt\" instead.");
            return;
        }
        if (job.showPrintDialog(owner)) {
            Text page = new Text(text);
            page.setFont(Font.font("Monospaced", 10));
            if (job.printPage(page)) {
                job.endJob();
            } else {
                AlertUtil.error("Print", "The bill could not be printed.");
            }
        }
    }

    private static void save(Window owner, OrderRecord order) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save bill");
        chooser.setInitialFileName(String.format("bill_%04d_%s.txt", order.getBillNo(),
                order.getCustomer().replaceAll("[^A-Za-z0-9]+", "_")));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text file", "*.txt"));
        File file = chooser.showSaveDialog(owner);
        if (file == null) return;
        try {
            Files.writeString(file.toPath(),
                    order.getBillText().replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
            AlertUtil.info("Bill saved", "Saved to " + file.getAbsolutePath());
        } catch (IOException e) {
            AlertUtil.error("Could not save", e.getMessage());
        }
    }
}
