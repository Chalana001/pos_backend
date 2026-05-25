package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "promotion_targets",
        indexes = {
                @Index(name = "idx_tenant_promotion_target_promotion", columnList = "tenant_id, promotion_id"),
                @Index(name = "idx_tenant_promotion_target_item", columnList = "tenant_id, item_id"),
                @Index(name = "idx_tenant_promotion_target_category", columnList = "tenant_id, category_id"),
                @Index(name = "idx_tenant_promotion_target_subcategory", columnList = "tenant_id, sub_category_id"),
                @Index(name = "idx_tenant_promotion_target_customer", columnList = "tenant_id, customer_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionTarget extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "sub_category_id")
    private Long subCategoryId;

    @Column(name = "customer_id")
    private Long customerId;
}
