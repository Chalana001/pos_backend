package com.chala.posapp.dto.barcodelabel;

import com.chala.posapp.entity.ScaleBarcodeValueType;

/**
 * One starting-point template for the scale-barcode format settings screen.
 * See {@link com.chala.posapp.barcode.ScaleBarcodeFormatPresets} — these are
 * editable starting templates, not verified vendor specifications.
 */
public record ScaleBarcodePresetResponse(
        String key,
        String label,
        String description,
        String prefix,
        int prefixLength,
        int itemCodeLength,
        int valueLength,
        ScaleBarcodeValueType valueType,
        boolean hasCheckDigit
) {
}
