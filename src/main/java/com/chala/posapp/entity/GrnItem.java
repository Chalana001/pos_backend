package com.chala.posapp.entity;

import com.chala.posapp.entity.MeasurementUnit;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "grn_items")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrnItem extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grn_id", nullable = false)
    private GRN grn;

    @ManyToOne
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    private Integer qty;

    @Column(name = "display_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayQty;

    // Supplier free-of-charge (FOC) units: received into stock, dilute the effective
    // unit cost, but add nothing to the line amount or the payable total.
    @Builder.Default
    @Column(name = "free_qty", nullable = false)
    private Integer freeQty = 0;

    @Builder.Default
    @Column(name = "display_free_qty", nullable = false, precision = 12, scale = 3)
    private BigDecimal displayFreeQty = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_unit", nullable = false, length = 10)
    private MeasurementUnit qtyUnit;

    private BigDecimal costPrice;
    private BigDecimal sellingPrice;

    private BigDecimal amount;
}
