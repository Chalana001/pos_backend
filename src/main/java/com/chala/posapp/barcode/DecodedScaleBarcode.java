package com.chala.posapp.barcode;

import com.chala.posapp.entity.ScaleBarcodeValueType;

import java.math.BigDecimal;

/**
 * Result of successfully decoding a scale barcode against a branch's
 * {@link com.chala.posapp.entity.BarcodeLabelSettings}.
 *
 * @param itemCode  the embedded item/PLU code, matched against the item's own
 *                  short {@code barcode} field, not a separate identifier.
 *                  Already stripped of leading zeros when the settings say so.
 * @param valueType what {@code value} represents
 * @param value     the decoded value with the settings' unit and implied decimals
 *                  already applied: grams for WEIGHT (may carry a fraction when
 *                  the scale prints finer than a gram), a currency amount for
 *                  PRICE
 */
public record DecodedScaleBarcode(String itemCode, ScaleBarcodeValueType valueType, BigDecimal value) {
}
