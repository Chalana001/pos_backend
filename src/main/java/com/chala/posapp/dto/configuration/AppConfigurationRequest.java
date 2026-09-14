package com.chala.posapp.dto.configuration;

import com.chala.posapp.entity.CategoryMode;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.ScaleBarcodeValueType;
import com.chala.posapp.entity.StockOverrideMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AppConfigurationRequest {
    private Long branchId;
    private boolean recipeItemsEnabled;
    private boolean weightItemsEnabled;
    private boolean servicesEnabled;
    private boolean tableManagementEnabled;
    private boolean dineInEnabled;
    private CategoryMode categoryMode;
    private StockOverrideMode stockOverrideMode;
    private Boolean adminStockOverrideAllowed;
    private Boolean managerStockOverrideAllowed;
    private Boolean cashierStockOverrideAllowed;
    private Boolean warrantyEnabled;
    private Boolean kotEnabled;
    private Boolean printReceiptAfterCheckout;
    private Boolean adminWarrantyAllowed;
    private Boolean managerWarrantyAllowed;
    private Boolean cashierWarrantyAllowed;

    // Scale-barcode decoding. Wrapper types so a client that does not know
    // about these fields leaves the stored values alone, the same way the
    // Boolean fields above behave. See barcode/ScaleBarcodeDecoder.
    private Boolean scaleBarcodeEnabled;

    @Size(max = 50)
    private String scaleBarcodePresetKey;

    // Comma separated list, letters allowed. Each entry must be exactly
    // scaleBarcodePrefixLength characters (checked in the service).
    @Size(max = 40)
    private String scaleBarcodePrefix;

    @Min(0)
    @Max(4)
    private Integer scaleBarcodePrefixLength;

    @Min(1)
    @Max(20)
    private Integer scaleBarcodeItemCodeLength;

    // 12 digits is the most a long can safely carry with decimals applied; no
    // real scale prints more.
    @Min(1)
    @Max(12)
    private Integer scaleBarcodeValueLength;

    private ScaleBarcodeValueType scaleBarcodeValueType;

    // G or KG; anything else is rejected in the service. Ignored for PRICE.
    private MeasurementUnit scaleBarcodeWeightUnit;

    @Min(0)
    @Max(5)
    private Integer scaleBarcodeValueDecimals;

    private Boolean scaleBarcodeStripLeadingZeros;

    private Boolean scaleBarcodeHasCheckDigit;
}
