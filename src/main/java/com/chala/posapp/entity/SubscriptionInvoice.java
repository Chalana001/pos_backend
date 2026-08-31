package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An invoice for a subscription payment — the document a shop owner can be sent.
 *
 * <p>Separate from {@link BillingRecord}: the record is the platform's internal ledger line,
 * this is the customer-facing artefact. One record produces at most one invoice, but an
 * invoice can be voided and reissued without touching the ledger.
 */
@Entity
@Table(name = "subscription_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionInvoice {

    public enum Status { ISSUED, PAID, VOID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-facing sequential reference, e.g. INV-2026-0042. */
    @Column(name = "invoice_no", nullable = false, unique = true, length = 40)
    private String invoiceNo;

    @Column(name = "tenant_id", nullable = false, length = 100)
    private String tenantId;

    @Column(name = "shop_name", nullable = false, length = 120)
    private String shopName;

    @Column(name = "billing_record_id")
    private Long billingRecordId;

    @Column(name = "plan_name", length = 120)
    private String planName;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(nullable = false)
    @Builder.Default
    private double subtotal = 0;

    @Column(name = "discount_amount", nullable = false)
    @Builder.Default
    private double discountAmount = 0;

    @Column(name = "tax_amount", nullable = false)
    @Builder.Default
    private double taxAmount = 0;

    @Column(nullable = false)
    @Builder.Default
    private double total = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.ISSUED;

    @Column(length = 500)
    private String notes;

    @Column(name = "billed_to_name", length = 160)
    private String billedToName;

    @Column(name = "billed_to_email", length = 160)
    private String billedToEmail;

    @Column(name = "billed_to_phone", length = 60)
    private String billedToPhone;

    @Column(name = "issued_by", length = 80)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @PrePersist
    void onCreate() {
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
}
