package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "receipt_template_settings",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"branch_id", "template_type"})
        },
        indexes = {
                @Index(name = "idx_branch_receipt_template", columnList = "branch_id, template_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptTemplateSettings extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, length = 20)
    private PrintTemplateType templateType;

    @Column(nullable = false)
    private boolean showLogo;

    @Column(nullable = false)
    private boolean showStoreName;

    @Column(nullable = false)
    private boolean showBranchName;

    @Column(nullable = false)
    private boolean showAddress;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean showAddressLabel;

    @Column(nullable = false)
    private boolean showPhone;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean showPhoneLabel;

    @Column(nullable = false)
    private boolean showInvoiceNumber;

    @Column(nullable = false)
    private boolean showDateTime;

    @Column(nullable = false)
    private boolean showCashier;

    @Column(nullable = false)
    private boolean showCustomer;

    @Column(nullable = false)
    private boolean showItemTable;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean showWarranty;

    @Column(nullable = false)
    private boolean showSubtotal;

    @Column(nullable = false)
    private boolean showDiscount;

    @Column(nullable = false)
    private boolean showNetTotal;

    @Column(nullable = false)
    private boolean showPaid;

    @Column(nullable = false)
    private boolean showBalance;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean showDueAmount;

    @Column(nullable = false)
    private boolean showThanksMessage;

    @Column(nullable = false)
    private boolean showCredits;

    @Column(nullable = false)
    private int logoWidthPercent;

    @Column(nullable = false, columnDefinition = "int default 4")
    private int logoTopSpacing;

    @Column(nullable = false, columnDefinition = "int default 78")
    private int invoiceLogoWidthPercent;

    @Column(name = "receipt_font_family", nullable = false, length = 40, columnDefinition = "varchar(40) default 'COURIER_NEW'")
    private String receiptFontFamily;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_name_source", nullable = false, length = 20, columnDefinition = "varchar(20) default 'PRIMARY'")
    @Builder.Default
    private ItemNameSource itemNameSource = ItemNameSource.PRIMARY;

    @Column(nullable = false)
    private int paperWidthMm;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean directPrintEnabled;

    @Column(length = 160)
    private String printerName;

    @Column(nullable = false, columnDefinition = "int default 1")
    private int printerCopies;

    @Column(length = 160)
    private String thanksMessage;

    @Column(length = 160)
    private String creditsLine1;

    @Column(length = 160)
    private String creditsLine2;

    @Lob
    @Column(name = "template_lines", columnDefinition = "LONGTEXT")
    private String templateLines;

    @Column(name = "currency_symbol", length = 10, columnDefinition = "varchar(10) default 'LKR'")
    private String currencySymbol;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
