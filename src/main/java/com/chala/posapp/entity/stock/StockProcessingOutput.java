package com.chala.posapp.entity.stock;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.TenantEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "stock_processing_outputs",
        indexes = {
                @Index(name = "idx_stock_processing_outputs", columnList = "processing_id"),
                @Index(name = "idx_stock_processing_output_item", columnList = "output_item_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockProcessingOutput extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "processing_id", nullable = false)
    private Long processingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "output_item_id", nullable = false)
    private Item outputItem;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;

    @Column(name = "is_waste", nullable = false)
    @Builder.Default
    private boolean waste = false;

    @Column(name = "allocated_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal allocatedCost;

    @Column(name = "created_batch_id")
    private Long createdBatchId;
}
