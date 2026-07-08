package com.chala.posapp.dto.receipt;

import com.chala.posapp.entity.ItemNameSource;
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
    private boolean showWarranty;
    private boolean showSubtotal;
    private boolean showDiscount;
    private boolean showNetTotal;
    private boolean showPaid;
    private boolean showBalance;
    private boolean showDueAmount;
    private boolean showThanksMessage;
    private boolean showCredits;

    @Min(20)
    @Max(200)
    private int logoWidthPercent;

    @Min(0)
    @Max(20)
    private int logoTopSpacing;

    @Min(20)
    @Max(200)
    private int invoiceLogoWidthPercent;

    @Size(max = 40)
    private String receiptFontFamily;

    @Min(48)
    @Max(210)
    private int paperWidthMm;

    private boolean directPrintEnabled;

    @Size(max = 160)
    private String printerName;

    @Min(1)
    @Max(10)
    private int printerCopies;

    @Size(max = 160)
    private String thanksMessage;

    @Size(max = 160)
    private String creditsLine1;

    @Size(max = 160)
    private String creditsLine2;

    private ItemNameSource itemNameSource;

    // JSON array string for the line-by-line receipt template editor.
    // When present and non-empty, this drives the receipt layout instead of the boolean toggles.
    private String templateLines;

    @Size(max = 10)
    private String currencySymbol;
}
