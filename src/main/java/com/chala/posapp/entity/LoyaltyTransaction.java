package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One movement of points. Positive for earning and for a reversal that gives spent points back,
 * negative for spending and for a reversal that takes earned points away.
 */
@Entity
@Table(
        name = "loyalty_transactions",
        indexes = {
                @Index(name = "idx_loyalty_txn_customer", columnList = "customer_id, at"),
                @Index(name = "idx_loyalty_txn_order", columnList = "order_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyTransaction extends TenantEntity {

    public enum Type { EARN, REDEEM, ADJUST, REVERSAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(nullable = false)
    private int points;

    /** The balance this row produced, so a disputed total can be walked back. */
    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(length = 255)
    private String note;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime at;

    /** Set when a cancellation undid this row. The row stays; the movement is neutralised. */
    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;
}
