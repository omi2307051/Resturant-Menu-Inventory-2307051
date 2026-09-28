package com.restaurant.inventory.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Builds the plain-text "bill paper" (receipt) for one order.
 * Pure Java (no JavaFX) so the same text can be shown on screen, printed, saved as a .txt
 * file, and stored in the database.
 */
public final class BillFormatter {

    /** Characters per line of the bill. */
    public static final int WIDTH = 46;

    /** One purchased dish: name, quantity, full menu price per unit and full line price. */
    public record Line(String name, int qty, double unitPrice, double lineTotal) { }

    private BillFormatter() { }

    private static String taka(double amount) {
        return String.format("\u09F3%,.2f", amount);
    }

    private static String center(String text) {
        int pad = Math.max(0, (WIDTH - text.length()) / 2);
        return " ".repeat(pad) + text;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + ".";
    }

    /**
     * @param discounts money taken off, per promotion name (e.g. "Burger Monday" -> 45.00,
     *                  "Cool Down Deal" -> 12.00); may be empty
     * @param subtotal  total at full menu prices
     * @param total     total actually payable (after discounts)
     */
    public static String build(int billNo, LocalDateTime time, String customer, String paymentMethod,
                               List<Line> lines, Map<String, Double> discounts, double subtotal, double total) {
        String bar = "=".repeat(WIDTH);
        String dash = "-".repeat(WIDTH);
        StringBuilder sb = new StringBuilder();

        sb.append(bar).append('\n');
        sb.append(center("PAVILLION 22 RESTAURANT")).append('\n');
        sb.append(center("CUSTOMER BILL")).append('\n');
        sb.append(bar).append('\n');
        sb.append(String.format("Bill No  : #%04d", billNo)).append('\n');
        sb.append("Date     : ").append(time.format(DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm:ss"))).append('\n');
        sb.append("Customer : ").append(customer).append('\n');
        sb.append(dash).append('\n');
        sb.append(String.format("%-20s %3s %9s %10s", "Item", "Qty", "Price", "Total")).append('\n');
        sb.append(dash).append('\n');

        for (Line line : lines) {
            String name = line.name();
            if (name.length() > 20) {
                sb.append(name).append('\n');
                name = "";
            }
            sb.append(String.format("%-20s %3d %9s %10s", name, line.qty(),
                    taka(line.unitPrice()), taka(line.lineTotal()))).append('\n');
        }

        sb.append(dash).append('\n');
        double discount = subtotal - total;
        if (discount > 0.005) {
            sb.append(String.format("%-28s %17s", "Subtotal", taka(subtotal))).append('\n');
            for (Map.Entry<String, Double> entry : discounts.entrySet()) {
                sb.append(String.format("%-28s %17s", clip("Discount (" + entry.getKey() + ")", 28),
                        "-" + taka(entry.getValue()))).append('\n');
            }
        }
        sb.append(String.format("%-28s %17s", "TOTAL TO PAY", taka(total))).append('\n');
        sb.append(dash).append('\n');
        sb.append("Paid via : ").append(paymentMethod).append('\n');
        if (PaymentQr.supports(paymentMethod)) {
            sb.append("Pay ref  : ").append(PaymentQr.reference(billNo)).append('\n');
        }
        sb.append("Status   : PAID").append('\n');
        sb.append(bar).append('\n');
        sb.append(center("Thank you, " + clip(customer, 24) + "!")).append('\n');
        sb.append(center("Please visit Pavillion 22 again")).append('\n');
        sb.append(bar).append('\n');
        return sb.toString();
    }
}
