package com.chala.posapp.barcode;

import com.chala.posapp.dto.barcodelabel.ScaleBarcodePresetResponse;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.ScaleBarcodeValueType;

import java.util.List;

/**
 * Static, in-memory list of starting-point templates offered on the
 * scale-barcode settings screen.
 *
 * IMPORTANT: these are EDITABLE STARTING TEMPLATES, not verified vendor
 * specifications. Real weighing-scale / label-printer devices vary by brand,
 * model and region, so a shop admin must confirm the digit layout against
 * their own device's manual before relying on one of these as-is, picking a
 * preset only pre-fills the settings form, it does not certify the shape is
 * correct for any particular piece of hardware. Presets are therefore named
 * after the barcode *shape* they describe (e.g. "prefix 20, weight in
 * grams"), never after a real manufacturer or model, because no specific
 * vendor's actual output has been verified against these numbers.
 */
public final class ScaleBarcodeFormatPresets {

    private ScaleBarcodeFormatPresets() {
    }

    public static final List<ScaleBarcodePresetResponse> ALL = List.of(
            new ScaleBarcodePresetResponse(
                    "WEIGHT_PREFIX_20",
                    "Weight in grams (prefix 20)",
                    "A common weight-embedded EAN-13 shape: 2-digit prefix \"20\", 5-digit item code, "
                            + "5-digit weight in whole grams, 1 check digit. Starting point only. Verify against "
                            + "your own device's manual before use.",
                    "20",
                    2,
                    5,
                    5,
                    ScaleBarcodeValueType.WEIGHT,
                    MeasurementUnit.G,
                    0,
                    false,
                    true
            ),
            new ScaleBarcodePresetResponse(
                    "WEIGHT_KG_PREFIX_2X",
                    "Weight in kg, 3 decimals (prefix 20 to 29)",
                    "Weight-embedded EAN-13 where the second prefix digit is a department: any prefix "
                            + "from \"20\" to \"29\", 5-digit item code, 5-digit weight in kg with 3 implied "
                            + "decimals (01234 = 1.234 kg), 1 check digit. Starting point only. Verify against "
                            + "your own device's manual before use.",
                    "20,21,22,23,24,25,26,27,28,29",
                    2,
                    5,
                    5,
                    ScaleBarcodeValueType.WEIGHT,
                    MeasurementUnit.KG,
                    3,
                    true,
                    true
            ),
            new ScaleBarcodePresetResponse(
                    "PRICE_PREFIX_21",
                    "Price with cents (prefix 21)",
                    "A common price-embedded EAN-13 shape: 2-digit prefix \"21\", 5-digit item code, "
                            + "5-digit price with 2 implied decimals (01234 = 12.34), 1 check digit. Starting "
                            + "point only. Verify against your own device's manual before use.",
                    "21",
                    2,
                    5,
                    5,
                    ScaleBarcodeValueType.PRICE,
                    MeasurementUnit.G,
                    2,
                    false,
                    true
            ),
            new ScaleBarcodePresetResponse(
                    "CUSTOM",
                    "Custom",
                    "No defaults. Fill in every field yourself to match your device's own barcode layout.",
                    null,
                    2,
                    5,
                    5,
                    ScaleBarcodeValueType.WEIGHT,
                    MeasurementUnit.G,
                    0,
                    false,
                    true
            )
    );
}
