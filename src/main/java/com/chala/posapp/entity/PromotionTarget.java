package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "promotion_targets",
        indexes = {
                @Index(name = "idx_promotion_target_promotion", columnList = "promotion_id"),
                @Index(name = "idx_promotion_target_item", columnList = "item_id"),
                @Index(name = "idx_promotion_target_category", columnList = "category_id"),
                @Index(name = "idx_promotion_target_subcategory", columnList = "sub_category_id"),
                @Index(name = "idx_promotion_target_customer", columnList = "customer_id")
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

    /**
     * A rule instead of a person. The alternative to {@link #customerId} on a CUSTOMER-scope
     * target: one names someone, the other names everyone a {@link CustomerSegment} picks out.
     *
     * <p>The engine never sees this. {@code PromotionGate} resolves the sale's customer into
     * their segments and rewrites a matching target as that customer's id, so segment matching
     * stays a database concern and the pricing engine keeps matching on explicit ids, which
     * is also what keeps the JS port and the fixture corpus valid without touching either.
     */
    @Column(name = "segment_id")
    private Long segmentId;

    /**
     * What this specific item sells for while the promotion runs, overriding the promotion's
     * own discount. Null means "no per-item price".
     *
     * <p>Resolved ahead of {@link #discountType}: a price the operator typed is more specific
     * than a rate, and it is how a seasonal price list is actually written down.
     */
    @Column(name = "offer_price", precision = 19, scale = 4)
    private BigDecimal offerPrice;

    /**
     * A per-item discount rate, used when {@link #offerPrice} is null. Null here too means the
     * item inherits the promotion's own {@code discountType}/{@code discountValue}, which is
     * every row written before per-item pricing existed, and is why no backfill was needed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", precision = 19, scale = 4)
    private BigDecimal discountValue;

    /** True when this row carries a price or rate of its own rather than inheriting the promotion's. */
    public boolean hasPriceOverride() {
        return offerPrice != null
                || (discountType != null && discountType != DiscountType.NONE && discountValue != null);
    }
}
