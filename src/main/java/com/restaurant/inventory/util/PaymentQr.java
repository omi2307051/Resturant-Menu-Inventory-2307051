package com.restaurant.inventory.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java rules for the payment QR code: WHICH payment methods get one and WHAT text it holds.
 * (Drawing the picture is done by {@link QrUtil}.)
 *
 * Only the mobile-wallet methods need a QR code - Cash and Card do not.
 *
 * IMPORTANT: this is a demo. The code holds the payment details as plain text, so any phone
 * QR scanner can read them, but the bKash / Nagad / Rocket apps will NOT treat it as an official
 * merchant QR (those are issued by the wallet company). Put the restaurant's real merchant numbers
 * in {@link #MERCHANT_NUMBERS} below before using this for anything real.
 */
public final class PaymentQr {

    public static final String MERCHANT_NAME = "Pavillion 22 Restaurant";

    /** Payment method name (as in InventoryService.PAYMENT_METHODS) -> the restaurant's wallet number. */
    private static final Map<String, String> MERCHANT_NUMBERS = Map.of(
            "bKash", "01XXXXXXXXX",
            "Nagad", "01XXXXXXXXX",
            "Rocket", "01XXXXXXXXX-X");

    private PaymentQr() { }

    /** True when this payment method should show a QR code. */
    public static boolean supports(String paymentMethod) {
        return paymentMethod != null && MERCHANT_NUMBERS.containsKey(paymentMethod);
    }

    public static List<String> supportedMethods() {
        return List.copyOf(MERCHANT_NUMBERS.keySet());
    }

    /** Payment reference printed on the bill and stored inside the QR, e.g. "P22-0012". */
    public static String reference(int billNo) {
        return String.format("P22-%04d", billNo);
    }

    /** The text inside the QR code. */
    public static String payload(String paymentMethod, String customer, double amount, String reference) {
        return "PAY TO: " + MERCHANT_NAME + "\n"
                + "METHOD: " + paymentMethod + " (Merchant)\n"
                + "NUMBER: " + MERCHANT_NUMBERS.get(paymentMethod) + "\n"
                + String.format(Locale.US, "AMOUNT: BDT %.2f", amount) + "\n"
                + "REF: " + reference + "\n"
                + "CUSTOMER: " + customer;
    }
}
