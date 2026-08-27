package com.chala.posapp.entity;

/**
 * What the numeric "value" segment embedded in a scale barcode represents.
 * WEIGHT_GRAMS: the segment is the item's weight, in grams.
 * PRICE_CENTS: the segment is the total price for the weighed item, in cents.
 */
public enum ScaleBarcodeValueType {
    WEIGHT_GRAMS,
    PRICE_CENTS
}
