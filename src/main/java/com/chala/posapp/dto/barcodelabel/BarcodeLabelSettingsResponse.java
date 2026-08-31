package com.chala.posapp.dto.barcodelabel;

import com.chala.posapp.entity.ItemNameSource;
import com.chala.posapp.entity.ScaleBarcodeValueType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BarcodeLabelSettingsResponse {

    private Long branchId;
    private String branchName;

    private int labelWidthMm;
    private int labelHeightMm;
    private int paddingTopMm;

    private boolean showShopName;
    private String shopNameText;
    private int shopNameFontSize;
    private boolean shopNameBold;

    private boolean showItemName;
    private ItemNameSource itemNameSource;
    private int itemNameMaxChars;
    private int itemNameFontSize;

    private String barcodeFormat;
    private BigDecimal barcodeWidth;
    private int barcodeHeight;
    private boolean showBarcodeValue;
    private int barcodeValueFontSize;

    private boolean showPrice;
    private String pricePrefix;
    private int priceFontSize;
    private boolean priceBold;

    private boolean showFooterText;
    private String footerText;
    private int footerFontSize;

    private boolean showExpiry;
    private String expiryPrefix;
    private int expiryFontSize;
    private String expiryDateFormat;

    private boolean directPrintEnabled;
    private String printerName;
    private int printerCopies;

    private String layoutJson;

    private boolean scaleBarcodeEnabled;
    private String scaleBarcodePresetKey;
    private String scaleBarcodePrefix;
    private int scaleBarcodePrefixLength;
    private int scaleBarcodeItemCodeLength;
    private int scaleBarcodeValueLength;
    private ScaleBarcodeValueType scaleBarcodeValueType;
    private boolean scaleBarcodeHasCheckDigit;
}
