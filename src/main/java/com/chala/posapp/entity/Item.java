package com.chala.posapp.entity;

import com.chala.posapp.entity.supplier.SupplierItem;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "items",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tenant_id", "barcode"})
        },
        indexes = {
                @Index(name = "idx_tenant_barcode", columnList = "tenant_id, barcode")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Item extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String barcode;

    @Column(nullable = false, length = 160)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_category_id")
    private SubCategory subCategory;

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "selling_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    @Column(nullable = false)
    private int reorderLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    @Builder.Default
    private ItemType itemType = ItemType.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_unit", nullable = false, length = 10)
    @Builder.Default
    private MeasurementUnit defaultUnit = MeasurementUnit.PCS;

    @Column(length = 500)
    private String imageUrl;

    @Column(name = "is_kot_enabled", nullable = false)
    @Builder.Default
    private boolean kotEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "pos_visible", nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private boolean posVisible = true;

    @Column(name = "stock_processing_enabled", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean stockProcessingEnabled = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SupplierItem> suppliers = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
