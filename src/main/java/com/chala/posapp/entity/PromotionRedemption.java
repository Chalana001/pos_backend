package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One promotion discounting one line or one bill, on the books.
 *
 * <p>The ledger exists because {@code order_items.promotion_id} is a single column: a line two
 * promotions stacked on could only be attributed to one of them. It also exists because a
 * refund should give a discount back rather than delete the fact it was given,
 * {@code reversedAt} marks that, and every count and budget excludes reversed rows.
 */
@Entity
@Table(
        name = "promotion_redemptions",
        indexes = {
                @Index(name = "idx_redemption_promotion_customer", columnList = "promotion_id, customer_id, reversed_at"),
                @Index(name = "idx_redemption_code_customer", columnList = "promotion_code_id, customer_id, reversed_at"),
                @Index(name = "idx_redemption_order", columnList = "order_id"),
                @Index(name = "idx_redemption_redeemed_at", columnList = "redeemed_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionRedemption extends TenantEntity {

    public enum Level { LINE, BILL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "promotion_id", nullable = false)
    private Long promotionId;

    @Column(name = "promotion_code_id")
    private Long promotionCodeId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Level level;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_order_id")
    private Long reversalOrderId;

    /**
     * Set on a row that gives part of another row back. Such a row carries a negative
     * {@link #discountAmount}, so every sum over the ledger nets the refund out on its own,
     * while the order still counts once as one the promotion was used on, which it was, for
     * the goods the customer kept.
     *
     * <p>A <em>full</em> return does not use this shape: it undoes the sale, so it takes the
     * whole-order path and marks the originals reversed.
     */
    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    /** The return that produced this reversal, for tracing a refund back to its paperwork. */
    @Column(name = "order_return_id")
    private Long orderReturnId;
}
