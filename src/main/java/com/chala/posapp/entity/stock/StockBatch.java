package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.TenantEntity;
import com.chala.posapp.entity.supplier.Supplier;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "stock_batches",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "batch_code"})
        },
        indexes = {
                @Index(name = "idx_tenant_batch_item_branch", columnList = "tenant_id, item_id, branch_id"),
                @Index(name = "idx_tenant_batch_expiry", columnList = "tenant_id, expire_date"),
                @Index(name = "idx_tenant_batch_origin_branch", columnList = "tenant_id, origin_batch_id, branch_id")
        }
)
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockBatch extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "batch_code")
    private String batchCode;

    @Column(name = "origin_batch_id")
    private Long originBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20, columnDefinition = "varchar(20) default 'PURCHASE'")
    @Builder.Default
    private StockBatchSourceType sourceType = StockBatchSourceType.PURCHASE;

    @Column(name = "cost_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "selling_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellingPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "original_quantity", nullable = false, updatable = false)
    private Integer originalQuantity;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "expire_date")
    private LocalDateTime expireDate;

    public boolean isOutOfStock() {
        return this.quantity != null && this.quantity <= 0;
    }
}
