package com.chala.posapp.barcode;

import com.chala.posapp.entity.AppConfiguration;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.ScaleBarcodeValueType;

/**
 * The digit layout a branch's weighing scale prints, as a plain value the
 * decoder can work on without knowing where it was stored. Built from the
 * branch's {@link AppConfiguration} (tenant V51) by {@link #from}.
 *
 * @param enabled           master switch
 * @param prefixes          comma separated list of accepted prefixes, letters
 *                          allowed, blank meaning "any"
 * @param prefixLength      characters of prefix at the start of the barcode
 * @param itemCodeLength    characters of item / PLU code after the prefix
 * @param valueLength       digits of weight or price after the item code (1 to 12)
 * @param valueType         whether the value digits are a weight or a price
 * @param weightUnit        G or KG, meaningful for WEIGHT only
 * @param valueDecimals     implied decimal places in the value digits (0 to 5)
 * @param stripLeadingZeros whether "00123" looks up item barcode "123"
 * @param hasCheckDigit     whether one trailing EAN-13 mod-10 check digit follows
 */
public record ScaleBarcodeFormat(
        boolean enabled,
        String prefixes,
        int prefixLength,
        int itemCodeLength,
        int valueLength,
        ScaleBarcodeValueType valueType,
        MeasurementUnit weightUnit,
        int valueDecimals,
        boolean stripLeadingZeros,
        boolean hasCheckDigit
) {

    public static ScaleBarcodeFormat from(AppConfiguration configuration) {
        if (configuration == null) {
            return DISABLED;
        }
        return new ScaleBarcodeFormat(
                configuration.isScaleBarcodeEnabled(),
                configuration.getScaleBarcodePrefix(),
                configuration.getScaleBarcodePrefixLength(),
                configuration.getScaleBarcodeItemCodeLength(),
                configuration.getScaleBarcodeValueLength(),
                configuration.getScaleBarcodeValueType() != null
                        ? configuration.getScaleBarcodeValueType() : ScaleBarcodeValueType.WEIGHT,
                configuration.getScaleBarcodeWeightUnit() != null
                        ? configuration.getScaleBarcodeWeightUnit() : MeasurementUnit.G,
                configuration.getScaleBarcodeValueDecimals(),
                configuration.isScaleBarcodeStripLeadingZeros(),
                configuration.isScaleBarcodeHasCheckDigit()
        );
    }

    public static final ScaleBarcodeFormat DISABLED = new ScaleBarcodeFormat(
            false, null, 2, 5, 5, ScaleBarcodeValueType.WEIGHT, MeasurementUnit.G, 0, false, true);
}
