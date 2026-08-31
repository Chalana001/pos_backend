package com.chala.posapp.dto.barcodelabel;

import com.chala.posapp.entity.ItemNameSource;
import com.chala.posapp.entity.ScaleBarcodeValueType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BarcodeLabelSettingsRequest {

    @Min(20)
    @Max(120)
    private int labelWidthMm;

    @Min(15)
    @Max(100)
    private int labelHeightMm;

    @Min(0)
    @Max(10)
    private int paddingTopMm;

    private boolean showShopName;

    @Size(max = 60)
    private String shopNameText;

    @Min(5)
    @Max(20)
    private int shopNameFontSize;

    private boolean shopNameBold;

    private boolean showItemName;

    private ItemNameSource itemNameSource;

    @Min(5)
    @Max(60)
    private int itemNameMaxChars;

    @Min(5)
    @Max(20)
    private int itemNameFontSize;

    @Size(max = 20)
    private String barcodeFormat;

    @DecimalMin("0.5")
    @DecimalMax("4.0")
    private BigDecimal barcodeWidth;

    @Min(15)
    @Max(80)
    private int barcodeHeight;

    private boolean showBarcodeValue;

    @Min(6)
    @Max(20)
    private int barcodeValueFontSize;

    private boolean showPrice;

    @Size(max = 10)
    private String pricePrefix;

    @Min(5)
    @Max(24)
    private int priceFontSize;

    private boolean priceBold;

    private boolean showFooterText;

    @Size(max = 80)
    private String footerText;

    @Min(5)
    @Max(16)
    private int footerFontSize;

    private boolean showExpiry;

    @Size(max = 20)
    private String expiryPrefix;

    @Min(5)
    @Max(20)
    private int expiryFontSize;

    @Size(max = 20)
    private String expiryDateFormat;

    private boolean directPrintEnabled;

    @Size(max = 160)
    private String printerName;

    @Min(1)
    @Max(10)
    private int printerCopies;

    // Ordered element-array layout as a JSON string (validated in the service:
    // must start with '['; otherwise stored as null → legacy layout).
    private String layoutJson;

    // Scale-barcode decoding (weight/price-embedded barcodes from the shop's
    // own weighing-scale device). See ScaleBarcodeDecoder / ScaleBarcodeFormatPresets.
    private boolean scaleBarcodeEnabled;

    @Size(max = 50)
    private String scaleBarcodePresetKey;

    @Size(max = 4)
    private String scaleBarcodePrefix;

    @Min(0)
    @Max(4)
    private int scaleBarcodePrefixLength;

    @Min(1)
    @Max(20)
    private int scaleBarcodeItemCodeLength;

    @Min(1)
    @Max(20)
    private int scaleBarcodeValueLength;

    private ScaleBarcodeValueType scaleBarcodeValueType;

    private boolean scaleBarcodeHasCheckDigit;
}
