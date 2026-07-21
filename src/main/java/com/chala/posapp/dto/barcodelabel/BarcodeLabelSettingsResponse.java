package com.chala.posapp.dto.barcodelabel;

import com.chala.posapp.entity.ItemNameSource;
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
}
