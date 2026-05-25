package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "stock_processing_output_links",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "source_item_id", "output_item_id"})
        },
        indexes = {
                @Index(name = "idx_tenant_processing_link_source", columnList = "tenant_id, source_item_id"),
                @Index(name = "idx_tenant_processing_link_output", columnList = "tenant_id, output_item_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockProcessingOutputLink extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_item_id", nullable = false)
    private Long sourceItemId;

    @Column(name = "output_item_id", nullable = false)
    private Long outputItemId;

    @Column(name = "default_quantity", nullable = false)
    @Builder.Default
    private Integer defaultQuantity = 1000;

    @Column(name = "is_waste", nullable = false)
    @Builder.Default
    private boolean waste = false;
}
