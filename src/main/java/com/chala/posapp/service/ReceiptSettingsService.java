package com.chala.posapp.service;

import com.chala.posapp.dto.receipt.ReceiptSettingsRequest;
import com.chala.posapp.dto.receipt.ReceiptSettingsResponse;
import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.ItemNameSource;
import com.chala.posapp.entity.PrintTemplateType;
import com.chala.posapp.entity.ReceiptTemplateSettings;
import com.chala.posapp.exception.ResourceNotFoundException;
import com.chala.posapp.repository.BranchRepository;
import com.chala.posapp.repository.ReceiptTemplateSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptSettingsService {

    private static final String DEFAULT_THANKS_MESSAGE = "Thank You, Come Again!";
    private static final String CREDITS_LINE_1 = "SOFTWARE BY ZENSYS SOLUTIONS";
    private static final String CREDITS_LINE_2 = "Smart Retail Solutions | 0704589764";
    private static final int DEFAULT_LOGO_WIDTH_PERCENT = 78;
    private static final int DEFAULT_LOGO_TOP_SPACING = 4;
    private static final int DEFAULT_THERMAL_WIDTH_MM = 80;
    private static final int DEFAULT_A4_WIDTH_MM = 210;
    private static final String DEFAULT_RECEIPT_FONT_FAMILY = "COURIER_NEW";

    private final BranchRepository branchRepository;
    private final ReceiptTemplateSettingsRepository receiptTemplateSettingsRepository;

    public ReceiptSettingsResponse getSettings(Long branchId, PrintTemplateType templateType) {
        Branch branch = getBranch(branchId);
        ReceiptTemplateSettings settings = receiptTemplateSettingsRepository
                .findByBranchIdAndTemplateType(branchId, templateType)
                .orElseGet(() -> buildDefaultSettings(branch, templateType));

        return mapToResponse(settings, branch);
    }

    @Transactional
    public ReceiptSettingsResponse updateSettings(Long branchId, PrintTemplateType templateType, ReceiptSettingsRequest request) {
        Branch branch = getBranch(branchId);
        ReceiptTemplateSettings settings = receiptTemplateSettingsRepository
                .findByBranchIdAndTemplateType(branchId, templateType)
                .orElseGet(() -> buildDefaultSettings(branch, templateType));

        settings.setBranchId(branch.getId());
        settings.setTemplateType(templateType);
        settings.setShowLogo(request.isShowLogo());
        settings.setShowStoreName(request.isShowStoreName());
        settings.setShowBranchName(request.isShowBranchName());
        settings.setShowAddress(request.isShowAddress());
        settings.setShowAddressLabel(request.isShowAddressLabel());
        settings.setShowPhone(request.isShowPhone());
        settings.setShowPhoneLabel(request.isShowPhoneLabel());
        settings.setShowInvoiceNumber(request.isShowInvoiceNumber());
        settings.setShowDateTime(request.isShowDateTime());
        settings.setShowCashier(request.isShowCashier());
        settings.setShowCustomer(request.isShowCustomer());
        settings.setShowItemTable(request.isShowItemTable());
        settings.setShowWarranty(request.isShowWarranty());
        settings.setShowSubtotal(request.isShowSubtotal());
        settings.setShowDiscount(request.isShowDiscount());
        settings.setShowNetTotal(request.isShowNetTotal());
        settings.setShowPaid(request.isShowPaid());
        settings.setShowBalance(request.isShowBalance());
        settings.setShowDueAmount(request.isShowDueAmount());
        settings.setShowThanksMessage(request.isShowThanksMessage());
        settings.setShowCredits(true);
        settings.setLogoWidthPercent(request.getLogoWidthPercent());
        settings.setLogoTopSpacing(request.getLogoTopSpacing());
        settings.setInvoiceLogoWidthPercent(request.getInvoiceLogoWidthPercent());
        settings.setReceiptFontFamily(normalizeFontFamily(request.getReceiptFontFamily()));
        settings.setPaperWidthMm(request.getPaperWidthMm());
        settings.setDirectPrintEnabled(request.isDirectPrintEnabled());
        settings.setPrinterName(normalizeNullableText(request.getPrinterName()));
        settings.setPrinterCopies(normalizeCopies(request.getPrinterCopies()));
        settings.setThanksMessage(normalizeMessage(request.getThanksMessage(), DEFAULT_THANKS_MESSAGE));
        settings.setCreditsLine1(CREDITS_LINE_1);
        settings.setCreditsLine2(CREDITS_LINE_2);
        settings.setItemNameSource(request.getItemNameSource() == null ? ItemNameSource.PRIMARY : request.getItemNameSource());
        settings.setTemplateLines(normalizeTemplateLines(request.getTemplateLines()));
        settings.setCurrencySymbol(normalizeCurrencySymbol(request.getCurrencySymbol()));

        ReceiptTemplateSettings saved = receiptTemplateSettingsRepository.save(settings);
        return mapToResponse(saved, branch);
    }

    private Branch getBranch(Long branchId) {
        return branchRepository.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch not found"));
    }

    private ReceiptTemplateSettings buildDefaultSettings(Branch branch, PrintTemplateType templateType) {
        return ReceiptTemplateSettings.builder()
                .branchId(branch.getId())
                .templateType(templateType)
                .showLogo(true)
                .showStoreName(true)
                .showBranchName(true)
                .showAddress(true)
                .showAddressLabel(true)
                .showPhone(true)
                .showPhoneLabel(true)
                .showInvoiceNumber(true)
                .showDateTime(true)
                .showCashier(true)
                .showCustomer(true)
                .showItemTable(true)
                .showWarranty(true)
                .showSubtotal(true)
                .showDiscount(true)
                .showNetTotal(true)
                .showPaid(true)
                .showBalance(true)
                .showDueAmount(true)
                .showThanksMessage(true)
                .showCredits(true)
                .logoWidthPercent(DEFAULT_LOGO_WIDTH_PERCENT)
                .logoTopSpacing(DEFAULT_LOGO_TOP_SPACING)
                .invoiceLogoWidthPercent(DEFAULT_LOGO_WIDTH_PERCENT)
                .receiptFontFamily(DEFAULT_RECEIPT_FONT_FAMILY)
                .paperWidthMm(defaultPaperWidth(templateType))
                .directPrintEnabled(false)
                .printerName(null)
                .printerCopies(1)
                .thanksMessage(DEFAULT_THANKS_MESSAGE)
                .creditsLine1(CREDITS_LINE_1)
                .creditsLine2(CREDITS_LINE_2)
                .itemNameSource(ItemNameSource.PRIMARY)
                .templateLines(null)
                .currencySymbol("LKR")
                .build();
    }

    private int defaultPaperWidth(PrintTemplateType templateType) {
        return templateType == PrintTemplateType.A4 ? DEFAULT_A4_WIDTH_MM : DEFAULT_THERMAL_WIDTH_MM;
    }

    private String normalizeMessage(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String normalizeFontFamily(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_RECEIPT_FONT_FAMILY;
        }

        return switch (value.trim().toUpperCase()) {
            case "ARIAL", "VERDANA", "TAHOMA", "COURIER_NEW" -> value.trim().toUpperCase();
            default -> DEFAULT_RECEIPT_FONT_FAMILY;
        };
    }

    private String normalizeNullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeCurrencySymbol(String value) {
        if (value == null || value.isBlank()) return "LKR";
        String trimmed = value.trim();
        return trimmed.length() > 6 ? trimmed.substring(0, 6) : trimmed;
    }

    private String normalizeTemplateLines(String templateLines) {
        if (templateLines == null || templateLines.isBlank()) {
            return null;
        }
        String trimmed = templateLines.trim();
        // Accept only JSON arrays; reject anything that doesn't start with '['
        if (!trimmed.startsWith("[")) {
            return null;
        }
        return trimmed;
    }

    private int normalizeCopies(int copies) {
        if (copies < 1) {
            return 1;
        }
        return Math.min(copies, 10);
    }

    private ReceiptSettingsResponse mapToResponse(ReceiptTemplateSettings settings, Branch branch) {
        return ReceiptSettingsResponse.builder()
                .branchId(settings.getBranchId())
                .branchName(branch.getName())
                .templateType(settings.getTemplateType())
                .showLogo(settings.isShowLogo())
                .showStoreName(settings.isShowStoreName())
                .showBranchName(settings.isShowBranchName())
                .showAddress(settings.isShowAddress())
                .showAddressLabel(settings.isShowAddressLabel())
                .showPhone(settings.isShowPhone())
                .showPhoneLabel(settings.isShowPhoneLabel())
                .showInvoiceNumber(settings.isShowInvoiceNumber())
                .showDateTime(settings.isShowDateTime())
                .showCashier(settings.isShowCashier())
                .showCustomer(settings.isShowCustomer())
                .showItemTable(settings.isShowItemTable())
                .showWarranty(settings.isShowWarranty())
                .showSubtotal(settings.isShowSubtotal())
                .showDiscount(settings.isShowDiscount())
                .showNetTotal(settings.isShowNetTotal())
                .showPaid(settings.isShowPaid())
                .showBalance(settings.isShowBalance())
                .showDueAmount(settings.isShowDueAmount())
                .showThanksMessage(settings.isShowThanksMessage())
                .showCredits(true)
                .logoWidthPercent(settings.getLogoWidthPercent())
                .logoTopSpacing(settings.getLogoTopSpacing())
                .invoiceLogoWidthPercent(settings.getInvoiceLogoWidthPercent())
                .receiptFontFamily(normalizeFontFamily(settings.getReceiptFontFamily()))
                .paperWidthMm(settings.getPaperWidthMm())
                .directPrintEnabled(settings.isDirectPrintEnabled())
                .printerName(settings.getPrinterName())
                .printerCopies(normalizeCopies(settings.getPrinterCopies()))
                .thanksMessage(settings.getThanksMessage())
                .creditsLine1(CREDITS_LINE_1)
                .creditsLine2(CREDITS_LINE_2)
                .itemNameSource(settings.getItemNameSource() != null ? settings.getItemNameSource() : ItemNameSource.PRIMARY)
                .templateLines(settings.getTemplateLines())
                .currencySymbol(settings.getCurrencySymbol() != null ? settings.getCurrencySymbol() : "LKR")
                .build();
    }
}
