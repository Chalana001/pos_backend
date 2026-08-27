package com.chala.posapp.service;

import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsRequest;
import com.chala.posapp.dto.barcodelabel.BarcodeLabelSettingsResponse;
import com.chala.posapp.entity.BarcodeLabelSettings;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.ItemNameSource;
import com.chala.posapp.entity.ScaleBarcodeValueType;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BarcodeLabelSettingsRepository;
import com.chala.posapp.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BarcodeLabelSettingsService {

    private static final String DEFAULT_BARCODE_FORMAT = "CODE128";
    private static final String DEFAULT_PRICE_PREFIX = "Rs.";
    private static final BigDecimal DEFAULT_BARCODE_WIDTH = new BigDecimal("1.5");
    private static final BigDecimal MIN_BARCODE_WIDTH = new BigDecimal("0.5");
    private static final BigDecimal MAX_BARCODE_WIDTH = new BigDecimal("4.0");
    private static final String DEFAULT_EXPIRY_PREFIX = "EXP:";
    private static final String DEFAULT_EXPIRY_DATE_FORMAT = "dd/MM/yyyy";
    private static final Set<String> ALLOWED_EXPIRY_DATE_FORMATS =
            Set.of("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd");

    private final BranchRepository branchRepository;
    private final BarcodeLabelSettingsRepository barcodeLabelSettingsRepository;

    public BarcodeLabelSettingsResponse getSettings(Long branchId) {
        Branch branch = getBranch(branchId);
        BarcodeLabelSettings settings = barcodeLabelSettingsRepository
                .findByBranchId(branchId)
                .orElseGet(() -> buildDefaultSettings(branch));

        return mapToResponse(settings, branch);
    }

    @Transactional
    public BarcodeLabelSettingsResponse updateSettings(Long branchId, BarcodeLabelSettingsRequest request) {
        Branch branch = getBranch(branchId);
        BarcodeLabelSettings settings = barcodeLabelSettingsRepository
                .findByBranchId(branchId)
                .orElseGet(() -> buildDefaultSettings(branch));

        settings.setBranchId(branch.getId());
        settings.setLabelWidthMm(clamp(request.getLabelWidthMm(), 20, 120));
        settings.setLabelHeightMm(clamp(request.getLabelHeightMm(), 15, 100));
        settings.setPaddingTopMm(clamp(request.getPaddingTopMm(), 0, 10));
        settings.setShowShopName(request.isShowShopName());
        settings.setShopNameText(normalizeNullableText(request.getShopNameText(), 60));
        settings.setShopNameFontSize(clamp(request.getShopNameFontSize(), 5, 20));
        settings.setShopNameBold(request.isShopNameBold());
        settings.setShowItemName(request.isShowItemName());
        settings.setItemNameSource(request.getItemNameSource() == null ? ItemNameSource.PRIMARY : request.getItemNameSource());
        settings.setItemNameMaxChars(clamp(request.getItemNameMaxChars(), 5, 60));
        settings.setItemNameFontSize(clamp(request.getItemNameFontSize(), 5, 20));
        settings.setBarcodeFormat(normalizeBarcodeFormat(request.getBarcodeFormat()));
        settings.setBarcodeWidth(normalizeBarcodeWidth(request.getBarcodeWidth()));
        settings.setBarcodeHeight(clamp(request.getBarcodeHeight(), 15, 80));
        settings.setShowBarcodeValue(request.isShowBarcodeValue());
        settings.setBarcodeValueFontSize(clamp(request.getBarcodeValueFontSize(), 6, 20));
        settings.setShowPrice(request.isShowPrice());
        settings.setPricePrefix(normalizePricePrefix(request.getPricePrefix()));
        settings.setPriceFontSize(clamp(request.getPriceFontSize(), 5, 24));
        settings.setPriceBold(request.isPriceBold());
        settings.setShowFooterText(request.isShowFooterText());
        settings.setFooterText(normalizeNullableText(request.getFooterText(), 80));
        settings.setFooterFontSize(clamp(request.getFooterFontSize(), 5, 16));
        settings.setShowExpiry(request.isShowExpiry());
        settings.setExpiryPrefix(normalizeExpiryPrefix(request.getExpiryPrefix()));
        settings.setExpiryFontSize(clamp(request.getExpiryFontSize(), 5, 20));
        settings.setExpiryDateFormat(normalizeExpiryDateFormat(request.getExpiryDateFormat()));
        settings.setDirectPrintEnabled(request.isDirectPrintEnabled());
        settings.setPrinterName(normalizeNullableText(request.getPrinterName(), 160));
        settings.setPrinterCopies(clamp(request.getPrinterCopies(), 1, 10));
        settings.setLayoutJson(normalizeLayoutJson(request.getLayoutJson()));
        settings.setScaleBarcodeEnabled(request.isScaleBarcodeEnabled());
        settings.setScaleBarcodePresetKey(normalizeNullableText(request.getScaleBarcodePresetKey(), 50));
        settings.setScaleBarcodePrefix(normalizeNullableText(request.getScaleBarcodePrefix(), 4));
        settings.setScaleBarcodePrefixLength(clamp(request.getScaleBarcodePrefixLength(), 0, 4));
        settings.setScaleBarcodeItemCodeLength(clamp(request.getScaleBarcodeItemCodeLength(), 1, 20));
        settings.setScaleBarcodeValueLength(clamp(request.getScaleBarcodeValueLength(), 1, 20));
        settings.setScaleBarcodeValueType(request.getScaleBarcodeValueType() == null
                ? ScaleBarcodeValueType.WEIGHT_GRAMS : request.getScaleBarcodeValueType());
        settings.setScaleBarcodeHasCheckDigit(request.isScaleBarcodeHasCheckDigit());

        BarcodeLabelSettings saved = barcodeLabelSettingsRepository.save(settings);
        return mapToResponse(saved, branch);
    }

    private Branch getBranch(Long branchId) {
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
    }

    private BarcodeLabelSettings buildDefaultSettings(Branch branch) {
        return BarcodeLabelSettings.builder()
                .branchId(branch.getId())
                .labelWidthMm(40)
                .labelHeightMm(25)
                .paddingTopMm(1)
                .showShopName(true)
                .shopNameText(null)
                .shopNameFontSize(7)
                .shopNameBold(true)
                .showItemName(true)
                .itemNameSource(ItemNameSource.PRIMARY)
                .itemNameMaxChars(22)
                .itemNameFontSize(8)
                .barcodeFormat(DEFAULT_BARCODE_FORMAT)
                .barcodeWidth(DEFAULT_BARCODE_WIDTH)
                .barcodeHeight(35)
                .showBarcodeValue(true)
                .barcodeValueFontSize(12)
                .showPrice(true)
                .pricePrefix(DEFAULT_PRICE_PREFIX)
                .priceFontSize(10)
                .priceBold(true)
                .showFooterText(false)
                .footerText(null)
                .footerFontSize(6)
                .showExpiry(false)
                .expiryPrefix(DEFAULT_EXPIRY_PREFIX)
                .expiryFontSize(7)
                .expiryDateFormat(DEFAULT_EXPIRY_DATE_FORMAT)
                .directPrintEnabled(false)
                .printerName(null)
                .printerCopies(1)
                .scaleBarcodeEnabled(false)
                .scaleBarcodePresetKey(null)
                .scaleBarcodePrefix(null)
                .scaleBarcodePrefixLength(2)
                .scaleBarcodeItemCodeLength(5)
                .scaleBarcodeValueLength(5)
                .scaleBarcodeValueType(ScaleBarcodeValueType.WEIGHT_GRAMS)
                .scaleBarcodeHasCheckDigit(true)
                .build();
    }

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private String normalizeBarcodeFormat(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BARCODE_FORMAT;
        }
        return switch (value.trim().toUpperCase()) {
            case "CODE128", "EAN13" -> value.trim().toUpperCase();
            default -> DEFAULT_BARCODE_FORMAT;
        };
    }

    private BigDecimal normalizeBarcodeWidth(BigDecimal value) {
        if (value == null) {
            return DEFAULT_BARCODE_WIDTH;
        }
        if (value.compareTo(MIN_BARCODE_WIDTH) < 0) return MIN_BARCODE_WIDTH;
        if (value.compareTo(MAX_BARCODE_WIDTH) > 0) return MAX_BARCODE_WIDTH;
        return value;
    }

    private String normalizePricePrefix(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PRICE_PREFIX;
        }
        String trimmed = value.trim();
        return trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed;
    }

    private String normalizeNullableText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String normalizeExpiryPrefix(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_EXPIRY_PREFIX;
        }
        String trimmed = value.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    private String normalizeExpiryDateFormat(String value) {
        if (value == null || !ALLOWED_EXPIRY_DATE_FORMATS.contains(value.trim())) {
            return DEFAULT_EXPIRY_DATE_FORMAT;
        }
        return value.trim();
    }

    // Guard the layout JSON the same way receipt template lines are guarded:
    // blank or non-array → null (renders from the legacy flat columns). The
    // array's internal shape is validated/clamped on the client.
    private String normalizeLayoutJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("[") ? trimmed : null;
    }

    private BarcodeLabelSettingsResponse mapToResponse(BarcodeLabelSettings settings, Branch branch) {
        return BarcodeLabelSettingsResponse.builder()
                .branchId(settings.getBranchId())
                .branchName(branch.getName())
                .labelWidthMm(settings.getLabelWidthMm())
                .labelHeightMm(settings.getLabelHeightMm())
                .paddingTopMm(settings.getPaddingTopMm())
                .showShopName(settings.isShowShopName())
                .shopNameText(settings.getShopNameText())
                .shopNameFontSize(settings.getShopNameFontSize())
                .shopNameBold(settings.isShopNameBold())
                .showItemName(settings.isShowItemName())
                .itemNameSource(settings.getItemNameSource() != null ? settings.getItemNameSource() : ItemNameSource.PRIMARY)
                .itemNameMaxChars(settings.getItemNameMaxChars())
                .itemNameFontSize(settings.getItemNameFontSize())
                .barcodeFormat(settings.getBarcodeFormat())
                .barcodeWidth(settings.getBarcodeWidth())
                .barcodeHeight(settings.getBarcodeHeight())
                .showBarcodeValue(settings.isShowBarcodeValue())
                .barcodeValueFontSize(settings.getBarcodeValueFontSize())
                .showPrice(settings.isShowPrice())
                .pricePrefix(settings.getPricePrefix())
                .priceFontSize(settings.getPriceFontSize())
                .priceBold(settings.isPriceBold())
                .showFooterText(settings.isShowFooterText())
                .footerText(settings.getFooterText())
                .footerFontSize(settings.getFooterFontSize())
                .showExpiry(settings.isShowExpiry())
                .expiryPrefix(settings.getExpiryPrefix())
                .expiryFontSize(settings.getExpiryFontSize())
                .expiryDateFormat(settings.getExpiryDateFormat())
                .directPrintEnabled(settings.isDirectPrintEnabled())
                .printerName(settings.getPrinterName())
                .printerCopies(clamp(settings.getPrinterCopies(), 1, 10))
                .layoutJson(settings.getLayoutJson())
                .scaleBarcodeEnabled(settings.isScaleBarcodeEnabled())
                .scaleBarcodePresetKey(settings.getScaleBarcodePresetKey())
                .scaleBarcodePrefix(settings.getScaleBarcodePrefix())
                .scaleBarcodePrefixLength(settings.getScaleBarcodePrefixLength())
                .scaleBarcodeItemCodeLength(settings.getScaleBarcodeItemCodeLength())
                .scaleBarcodeValueLength(settings.getScaleBarcodeValueLength())
                .scaleBarcodeValueType(settings.getScaleBarcodeValueType() != null
                        ? settings.getScaleBarcodeValueType() : ScaleBarcodeValueType.WEIGHT_GRAMS)
                .scaleBarcodeHasCheckDigit(settings.isScaleBarcodeHasCheckDigit())
                .build();
    }
}
