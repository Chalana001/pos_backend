package com.chala.posapp.dto.receipt;

import com.chala.posapp.entity.ItemNameSource;
import com.chala.posapp.entity.PrintTemplateType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiptSettingsResponse {
    private Long branchId;
    private String branchName;
    private PrintTemplateType templateType;
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
    private int logoWidthPercent;
    private int logoTopSpacing;
    private int invoiceLogoWidthPercent;
    private String receiptFontFamily;
    private int paperWidthMm;
    private boolean directPrintEnabled;
    private String printerName;
    private int printerCopies;
    private String thanksMessage;
    private String creditsLine1;
    private String creditsLine2;
    private ItemNameSource itemNameSource;
    private String templateLines;
    private String currencySymbol;
}
