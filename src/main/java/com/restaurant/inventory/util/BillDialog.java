package com.restaurant.inventory.util;

import com.restaurant.inventory.model.OrderRecord;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
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
 *
 * Bills paid with a mobile wallet (bKash / Nagad / Rocket) also show a payment QR code under
 * the bill text (and on the printed copy). It is rebuilt from the order's data each time the
 * bill is opened, so old bills from the database get their QR code too. The .txt file has the
 * "Pay ref" line but no picture.
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

        // payment QR (wallet payments only)
        VBox content = new VBox(10, area);
        Image qrImage = null;
        String qrCaptionText = null;
        if (PaymentQr.supports(order.getPaymentMethod())) {
            String reference = PaymentQr.reference(order.getBillNo());
            try {
                qrImage = QrUtil.toImage(PaymentQr.payload(order.getPaymentMethod(), order.getCustomer(),
                        order.getTotal(), reference), 320);
                qrCaptionText = order.getPaymentMethod() + " payment QR   |   Ref " + reference
                        + "   |   " + String.format("\u09F3%,.2f", order.getTotal());
                ImageView qrView = new ImageView(qrImage);
                qrView.setFitWidth(150);
                qrView.setFitHeight(150);
                qrView.setPreserveRatio(true);
                Label caption = new Label(qrCaptionText);
                VBox qrBox = new VBox(4, qrView, caption);
                qrBox.setAlignment(Pos.CENTER);
                content.getChildren().add(qrBox);
            } catch (Exception e) {
                qrImage = null;   // the bill still works without the QR picture
            }
        }
        final Image printQr = qrImage;
        final String printCaption = qrCaptionText;

        Dialog<Void> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Bill #" + order.getBillNo());
        dialog.setHeaderText("Bill for " + order.getCustomer() + "  -  paid via " + order.getPaymentMethod());
        dialog.getDialogPane().setContent(content);

        ButtonType printType = new ButtonType("Print", ButtonBar.ButtonData.LEFT);
        ButtonType saveType = new ButtonType("Save as .txt", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(printType, saveType, ButtonType.CLOSE);

        // Print / Save must NOT close the dialog, so consume their action events.
        Button printButton = (Button) dialog.getDialogPane().lookupButton(printType);
        printButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            print(owner, text, printQr, printCaption);
        });
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            save(owner, order);
        });

        dialog.showAndWait();
    }

    private static void print(Window owner, String text, Image qrImage, String qrCaption) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            AlertUtil.warning("Print", "No printer is available. Use \"Save as .txt\" instead.");
            return;
        }
        if (job.showPrintDialog(owner)) {
            Text billText = new Text(text);
            billText.setFont(Font.font("Monospaced", 10));
            VBox page = new VBox(8, billText);
            if (qrImage != null) {
                ImageView qrView = new ImageView(qrImage);
                qrView.setFitWidth(110);
                qrView.setFitHeight(110);
                qrView.setPreserveRatio(true);
                Text caption = new Text(qrCaption);
                caption.setFont(Font.font("Monospaced", 8));
                page.getChildren().addAll(qrView, caption);
            }
            if (job.printPage((Node) page)) {
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
