package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_batches", indexes = {
        @Index(name = "idx_batch_item_branch", columnList = "item_id, branch_id"), // Faster search for POS
        @Index(name = "idx_batch_expiry", columnList = "expire_date") // To find expiring items
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- RELATIONSHIPS ---

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier; // Optional: To track where this batch came from

    // --- BATCH DETAILS ---

    @Column(name = "batch_code", unique = true)
    private String batchCode; // Can be GRN ID or Manual Batch Code (e.g., "GRN-2026-001")

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "selling_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    // --- QUANTITY TRACKING ---

    @Column(nullable = false)
    private Integer quantity; // Current remaining stock (This decreases when sold)

    @Column(name = "original_quantity", nullable = false, updatable = false)
    private Integer originalQuantity; // Initial stock received (Never changes, for audit)

    // --- DATES ---

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "expire_date")
    private LocalDateTime expireDate; // Nullable (Hardware doesn't expire, but Gum/Ink might)

    // --- HELPER METHODS ---

    // Optional: Check if batch is empty
    public boolean isOutOfStock() {
        return this.quantity <= 0;
    }
}