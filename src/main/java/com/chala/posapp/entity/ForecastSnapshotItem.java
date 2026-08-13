package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity @Table(name = "forecast_snapshot_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ForecastSnapshotItem extends TenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "snapshot_id") private ForecastSnapshot snapshot;
    @Column(name = "item_id", nullable = false) private Long itemId;
    @Column(name = "item_name", nullable = false) private String itemName;
    @Column(name = "unit", length = 20) private String unit;
    @Column(name = "predicted_qty", nullable = false, precision = 19, scale = 3) private BigDecimal predictedQty;
    @Column(name = "actual_qty", precision = 19, scale = 3) private BigDecimal actualQty;
    @Column(name = "absolute_error", precision = 19, scale = 3) private BigDecimal absoluteError;
    @Column(name = "confidence", nullable = false, length = 20) private String confidence;
    @Column(name = "scored", nullable = false) private boolean scored;
}
