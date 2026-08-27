package com.chala.posapp.barcode;

import com.chala.posapp.entity.ScaleBarcodeValueType;

/**
 * Result of successfully decoding a scale barcode against a branch's
 * {@link com.chala.posapp.entity.BarcodeLabelSettings}.
 *
 * @param itemCode the embedded item/PLU code — matched against the item's own
 *                 short {@code barcode} field, not a separate identifier
 * @param valueType what {@code rawValue} represents
 * @param rawValue  the decoded numeric value segment, in the unit implied by
 *                  {@code valueType} (grams, or cents)
 */
public record DecodedScaleBarcode(String itemCode, ScaleBarcodeValueType valueType, int rawValue) {
}
