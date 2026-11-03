package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One negative-batch quantity change made by a purchase line's stock offset.
 *
 * <p>Cancellation verifies every live row's batch still holds
 * {@code batchQuantityAfterOffset} before voiding, then undoes the offset by
 * removing {@code quantityOffset} again and marks the row reversed.
 */
@Entity
@Table(name = "purchase_stock_offsets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseStockOffset extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_id", nullable = false)
    private Long purchaseId;

    @Column(name = "grn_id")
    private Long grnId;

    @Column(name = "grn_item_id")
    private Long grnItemId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "negative_batch_id", nullable = false)
    private Long negativeBatchId;

    /** Signed movement applied to the batch: positive reduces a negative balance. */
    @Column(name = "quantity_offset", nullable = false)
    private Integer quantityOffset;

    @Column(name = "batch_quantity_after_offset", nullable = false)
    private Integer batchQuantityAfterOffset;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
