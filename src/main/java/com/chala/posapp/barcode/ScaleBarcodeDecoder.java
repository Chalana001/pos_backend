package com.chala.posapp.barcode;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.ScaleBarcodeValueType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Generic decoder for weight-/price-embedded "scale barcodes", driven entirely
 * by a branch's {@link ScaleBarcodeFormat}, no hardcoded vendor format.
 *
 * A scale barcode is treated as: [prefix][item code][value][check digit?],
 * with each segment's length coming from the format. Only the value and the
 * check digit have to be digits; the prefix and the item code are compared as
 * text, so a scale that prints a lettered PLU ("NS12") or a lettered prefix
 * works the same as one that prints numbers.
 *
 * The frontend has a line-for-line mirror in {@code utils/scaleBarcode.js}
 * that powers the settings screen's test box. Change one and change the other.
 */
public final class ScaleBarcodeDecoder {

    private static final BigDecimal GRAMS_PER_KILOGRAM = BigDecimal.valueOf(1000);

    private ScaleBarcodeDecoder() {
    }

    public static Optional<DecodedScaleBarcode> tryDecode(String barcode, ScaleBarcodeFormat format) {
        if (format == null || !format.enabled()) {
            return Optional.empty();
        }

        if (barcode == null || barcode.isEmpty()) {
            return Optional.empty();
        }

        int prefixLength = format.prefixLength();
        int itemCodeLength = format.itemCodeLength();
        int valueLength = format.valueLength();
        boolean hasCheckDigit = format.hasCheckDigit();

        if (prefixLength < 0 || itemCodeLength <= 0 || valueLength <= 0 || valueLength > 12) {
            return Optional.empty();
        }

        int expectedLength = prefixLength + itemCodeLength + valueLength + (hasCheckDigit ? 1 : 0);
        if (barcode.length() != expectedLength) {
            return Optional.empty();
        }

        List<String> allowedPrefixes = parsePrefixes(format.prefixes());
        if (prefixLength > 0 && !allowedPrefixes.isEmpty()) {
            String actualPrefix = barcode.substring(0, prefixLength).toUpperCase(Locale.ROOT);
            if (!allowedPrefixes.contains(actualPrefix)) {
                return Optional.empty();
            }
        }

        if (hasCheckDigit) {
            // EAN-13 mod-10 is only defined over digits. A scale that prints a
            // lettered prefix ("NS1 00001 00050 2") still ends its label with a
            // check digit of its own making, so when the payload carries letters
            // the trailing character is taken as present but not verified; the
            // length rule carries the detection. All-digit layouts are verified.
            String payload = barcode.substring(0, barcode.length() - 1);
            if (isAllDigits(payload)) {
                char expectedCheckDigit = barcode.charAt(barcode.length() - 1);
                if (!Character.isDigit(expectedCheckDigit)
                        || expectedCheckDigit != computeEan13CheckDigit(payload)) {
                    return Optional.empty();
                }
            }
        }

        String itemCode = barcode.substring(prefixLength, prefixLength + itemCodeLength);
        if (format.stripLeadingZeros()) {
            itemCode = stripLeadingZeros(itemCode);
        }

        String valueDigits = barcode.substring(prefixLength + itemCodeLength, prefixLength + itemCodeLength + valueLength);
        if (!isAllDigits(valueDigits)) {
            return Optional.empty();
        }

        long rawValue;
        try {
            rawValue = Long.parseLong(valueDigits);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        ScaleBarcodeValueType valueType = format.valueType();
        if (valueType == null) {
            return Optional.empty();
        }

        int decimals = Math.max(0, Math.min(format.valueDecimals(), 5));
        BigDecimal scaled = BigDecimal.valueOf(rawValue, decimals);

        BigDecimal value;
        if (valueType == ScaleBarcodeValueType.WEIGHT) {
            MeasurementUnit unit = format.weightUnit() == null ? MeasurementUnit.G : format.weightUnit();
            value = unit == MeasurementUnit.KG ? scaled.multiply(GRAMS_PER_KILOGRAM) : scaled;
        } else {
            value = scaled;
        }

        return Optional.of(new DecodedScaleBarcode(itemCode, valueType, value));
    }

    /**
     * Splits the stored prefix column ("20,21" or "NS") into upper-cased entries.
     * Blank means "any prefix of the configured length".
     */
    public static List<String> parsePrefixes(String configuredPrefix) {
        if (configuredPrefix == null || configuredPrefix.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configuredPrefix.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();
    }

    private static String stripLeadingZeros(String itemCode) {
        int i = 0;
        while (i < itemCode.length() - 1 && itemCode.charAt(i) == '0') {
            i++;
        }
        return itemCode.substring(i);
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
    static char computeEan13CheckDigit(String payload) {
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
