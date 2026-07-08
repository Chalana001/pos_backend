package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.Branch;
import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "stock_processings",
        indexes = {
                @Index(name = "idx_stock_processing_branch", columnList = "branch_id, processed_at"),
                @Index(name = "idx_stock_processing_source", columnList = "source_item_id, processed_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockProcessing extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_item_id", nullable = false)
    private Item sourceItem;

    @Column(name = "source_batch_id", nullable = false)
    private Long sourceBatchId;

    @Column(name = "source_batch_code")
    private String sourceBatchCode;

    @Column(name = "source_qty", nullable = false)
    private Integer sourceQty;

    @Column(name = "source_display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal sourceDisplayQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_qty_unit", nullable = false, length = 10)
    private MeasurementUnit sourceQtyUnit;

    @Column(name = "source_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal sourceCost;

    @Column(name = "processed_by_user_id", nullable = false)
    private Long processedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    @Builder.Default
    private StockProcessingStatus status = StockProcessingStatus.COMPLETED;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "canceled_by_user_id")
    private Long canceledByUserId;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(length = 500)
    private String note;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = StockProcessingStatus.COMPLETED;
        }
    }
}
