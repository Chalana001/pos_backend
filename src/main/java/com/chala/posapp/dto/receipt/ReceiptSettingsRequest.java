package com.chala.posapp.dto.receipt;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReceiptSettingsRequest {

    private boolean showLogo;
    private boolean showStoreName;
    private boolean showBranchName;
    private boolean showAddress;
    private boolean showAddressLabel;
    private boolean showPhone;
    private boolean showPhoneLabel;
    private boolean showInvoiceNumber;
    private boolean showDateTime;
    private boolean showCashier;
    private boolean showCustomer;
    private boolean showItemTable;
    private boolean showSubtotal;
    private boolean showDiscount;
    private boolean showNetTotal;
    private boolean showPaid;
    private boolean showBalance;
    private boolean showThanksMessage;
    private boolean showCredits;

    @Min(20)
    @Max(200)
    private int logoWidthPercent;

    @Min(20)
    @Max(200)
    private int invoiceLogoWidthPercent;

    @Min(48)
    @Max(210)
    private int paperWidthMm;

    @Size(max = 160)
    private String thanksMessage;

    @Size(max = 160)
    private String creditsLine1;

    @Size(max = 160)
    private String creditsLine2;
}
