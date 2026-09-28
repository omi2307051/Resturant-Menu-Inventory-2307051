package com.restaurant.inventory.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MODEL: one completed order - who ordered, what, how they paid, and the printed bill.
 * These are what the Orders tab's "Order history" list shows, and each one is also stored
 * as a row in the SQLite "orders" table (so the bill can be re-opened after a restart).
 */
public class OrderRecord {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm");

    private final int billNo;
    private final String customer;
    private final String paymentMethod;
    private final String itemsSummary;
    private final double total;
    private final LocalDateTime placedAt;
    private final String billText;

    public OrderRecord(int billNo, String customer, String paymentMethod, String itemsSummary,
                       double total, LocalDateTime placedAt, String billText) {
        this.billNo = billNo;
        this.customer = customer;
        this.paymentMethod = paymentMethod;
        this.itemsSummary = itemsSummary;
        this.total = total;
        this.placedAt = placedAt;
        this.billText = billText;
    }

    public int getBillNo() { return billNo; }
    public String getCustomer() { return customer; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getItemsSummary() { return itemsSummary; }
    public double getTotal() { return total; }
    public LocalDateTime getPlacedAt() { return placedAt; }
    public String getBillText() { return billText; }

    /** Text shown in the history list, e.g. "#12  28 Sep, 14:35 / Jakaria: 1 x Kacchi Biryani / bKash - 450.00". */
    @Override
    public String toString() {
        return String.format("#%d   %s\nCustomer: %s\n%s\nPaid via %s  -  \u09F3%,.2f",
                billNo, placedAt.format(TIME), customer, itemsSummary, paymentMethod, total);
    }
}
