package com.restaurant.inventory.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.Map;

/**
 * Draws a QR code as a JavaFX {@link Image}, using the ZXing library (see pom.xml).
 * Runs completely offline - nothing about the payment is sent to any website.
 */
public final class QrUtil {

    private QrUtil() { }

    /** @param size width and height of the picture in pixels */
    public static Image toImage(String text, int size) throws WriterException {
        Map<EncodeHintType, Object> hints = Map.of(
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET, "UTF-8",
                EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);

        WritableImage image = new WritableImage(matrix.getWidth(), matrix.getHeight());
        PixelWriter pixels = image.getPixelWriter();
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                pixels.setColor(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return image;
    }
}
