package com.chala.posapp.entity;

/**
 * What the numeric "value" segment embedded in a scale barcode represents.
 * WEIGHT: the segment is the item's weight, in the unit and with the implied
 *         decimals the branch's BarcodeLabelSettings say (scaleBarcodeWeightUnit,
 *         scaleBarcodeValueDecimals).
 * PRICE:  the segment is the total price for the weighed item, with the implied
 *         decimals from scaleBarcodeValueDecimals (2 for "cents", 0 for whole
 *         rupees).
 *
 * Renamed from WEIGHT_GRAMS / PRICE_CENTS in tenant V51, which rewrites stored
 * rows; the unit and decimals used to be implied by the name.
 */
public enum ScaleBarcodeValueType {
    WEIGHT,
    PRICE
}
