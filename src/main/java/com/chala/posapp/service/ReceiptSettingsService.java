package com.chala.posapp.service;

import com.chala.posapp.dto.receipt.ReceiptSettingsRequest;
import com.chala.posapp.dto.receipt.ReceiptSettingsResponse;
import com.chala.posapp.entity.Branch;
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
    private static final String DEFAULT_CREDITS_LINE_1 = "SOFTWARE BY CHALA";
    private static final String DEFAULT_CREDITS_LINE_2 = "Smart Retail Solutions | 0704589764";
    private static final int DEFAULT_LOGO_WIDTH_PERCENT = 78;
    private static final int DEFAULT_THERMAL_WIDTH_MM = 80;
    private static final int DEFAULT_A4_WIDTH_MM = 210;

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
        settings.setShowSubtotal(request.isShowSubtotal());
        settings.setShowDiscount(request.isShowDiscount());
        settings.setShowNetTotal(request.isShowNetTotal());
        settings.setShowPaid(request.isShowPaid());
        settings.setShowBalance(request.isShowBalance());
        settings.setShowThanksMessage(request.isShowThanksMessage());
        settings.setShowCredits(true);
        settings.setLogoWidthPercent(request.getLogoWidthPercent());
        settings.setInvoiceLogoWidthPercent(request.getInvoiceLogoWidthPercent());
        settings.setPaperWidthMm(request.getPaperWidthMm());
        settings.setThanksMessage(normalizeMessage(request.getThanksMessage(), DEFAULT_THANKS_MESSAGE));
        settings.setCreditsLine1(DEFAULT_CREDITS_LINE_1);
        settings.setCreditsLine2(DEFAULT_CREDITS_LINE_2);

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
                .showSubtotal(true)
                .showDiscount(true)
                .showNetTotal(true)
                .showPaid(true)
                .showBalance(true)
                .showThanksMessage(true)
                .showCredits(true)
                .logoWidthPercent(DEFAULT_LOGO_WIDTH_PERCENT)
                .invoiceLogoWidthPercent(DEFAULT_LOGO_WIDTH_PERCENT)
                .paperWidthMm(defaultPaperWidth(templateType))
                .thanksMessage(DEFAULT_THANKS_MESSAGE)
                .creditsLine1(DEFAULT_CREDITS_LINE_1)
                .creditsLine2(DEFAULT_CREDITS_LINE_2)
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
                .showSubtotal(settings.isShowSubtotal())
                .showDiscount(settings.isShowDiscount())
                .showNetTotal(settings.isShowNetTotal())
                .showPaid(settings.isShowPaid())
                .showBalance(settings.isShowBalance())
                .showThanksMessage(settings.isShowThanksMessage())
                .showCredits(true)
                .logoWidthPercent(settings.getLogoWidthPercent())
                .invoiceLogoWidthPercent(settings.getInvoiceLogoWidthPercent())
                .paperWidthMm(settings.getPaperWidthMm())
                .thanksMessage(settings.getThanksMessage())
                .creditsLine1(DEFAULT_CREDITS_LINE_1)
                .creditsLine2(DEFAULT_CREDITS_LINE_2)
                .build();
    }
}
