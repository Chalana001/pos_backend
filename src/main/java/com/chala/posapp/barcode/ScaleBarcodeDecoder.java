package com.chala.posapp.barcode;

import com.chala.posapp.entity.BarcodeLabelSettings;

import java.util.Optional;

/**
 * Generic decoder for weight-/price-embedded "scale barcodes", driven entirely
 * by a branch's {@link BarcodeLabelSettings} — no hardcoded vendor format.
 *
 * A scale barcode is treated as: [prefix][item code][value][check digit?],
 * all digits, with each segment's length coming from the settings.
 */
public final class ScaleBarcodeDecoder {

    private ScaleBarcodeDecoder() {
    }

    public static Optional<DecodedScaleBarcode> tryDecode(String barcode, BarcodeLabelSettings settings) {
        if (settings == null || !settings.isScaleBarcodeEnabled()) {
            return Optional.empty();
        }

        if (barcode == null || barcode.isEmpty() || !isAllDigits(barcode)) {
            return Optional.empty();
        }

        int prefixLength = settings.getScaleBarcodePrefixLength();
        int itemCodeLength = settings.getScaleBarcodeItemCodeLength();
        int valueLength = settings.getScaleBarcodeValueLength();
        boolean hasCheckDigit = settings.isScaleBarcodeHasCheckDigit();

        if (prefixLength < 0 || itemCodeLength <= 0 || valueLength <= 0) {
            return Optional.empty();
        }

        int expectedLength = prefixLength + itemCodeLength + valueLength + (hasCheckDigit ? 1 : 0);
        if (barcode.length() != expectedLength) {
            return Optional.empty();
        }

        String configuredPrefix = settings.getScaleBarcodePrefix();
        if (prefixLength > 0 && configuredPrefix != null && !configuredPrefix.isBlank()) {
            String actualPrefix = barcode.substring(0, prefixLength);
            if (!actualPrefix.equals(configuredPrefix.trim())) {
                return Optional.empty();
            }
        }

        if (hasCheckDigit) {
            String payload = barcode.substring(0, barcode.length() - 1);
            char expectedCheckDigit = barcode.charAt(barcode.length() - 1);
            char computedCheckDigit = computeEan13CheckDigit(payload);
            if (expectedCheckDigit != computedCheckDigit) {
                return Optional.empty();
            }
        }

        String itemCode = barcode.substring(prefixLength, prefixLength + itemCodeLength);
        String valueDigits = barcode.substring(prefixLength + itemCodeLength, prefixLength + itemCodeLength + valueLength);

        int rawValue;
        try {
            rawValue = Integer.parseInt(valueDigits);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        var valueType = settings.getScaleBarcodeValueType();
        if (valueType == null) {
            return Optional.empty();
        }

        return Optional.of(new DecodedScaleBarcode(itemCode, valueType, rawValue));
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Standard EAN-13 mod-10 check digit: from the left, digits at even
     * positions (0-indexed: 0, 2, 4, ...) carry weight 1 and digits at odd
     * positions carry weight 3; the check digit is whatever brings the total
     * to the next multiple of 10.
     */
    private static char computeEan13CheckDigit(String payload) {
        int sum = 0;
        for (int i = 0; i < payload.length(); i++) {
            int digit = payload.charAt(i) - '0';
            int weight = (i % 2 == 0) ? 1 : 3;
            sum += digit * weight;
        }
        int checkDigit = (10 - (sum % 10)) % 10;
        return (char) ('0' + checkDigit);
    }
}
