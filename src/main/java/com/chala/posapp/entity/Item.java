package com.chala.posapp.entity;

import com.chala.posapp.entity.supplier.SupplierItem;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MISS-06: @SQLRestriction("deleted_at IS NULL") ensures all JPA queries
 * automatically exclude logically-deleted items without changing any service code.
 * Use item.setActive(false) + item.setDeletedAt(now) together when deactivating.
 */
@Entity
@Table(
        name = "items",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"barcode"})
        },
        indexes = {
                @Index(name = "idx_barcode", columnList = "barcode")
        }
)
@SQLRestriction("deleted_at IS NULL")  // MISS-06
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

    @Column(name = "alt_name", length = 160)
    private String altName;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "overhead_cost_mode", nullable = false, length = 20)
    @Builder.Default
    private ItemOverheadCostMode overheadCostMode = ItemOverheadCostMode.NONE;

    @Column(name = "overhead_cost_value", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal overheadCostValue = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * MISS-06: Soft-delete timestamp. When set (non-null) the item is logically
     * deleted and @SQLRestriction("deleted_at IS NULL") hides it from all JPA queries.
     * Always set active=false at the same time for backward compatibility with native queries.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SupplierItem> suppliers = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
