package com.chala.posapp.repository;

import com.chala.posapp.entity.Promotion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Every finder here filters {@code deletedAt IS NULL}. A retired promotion keeps its row so
 * that reporting can still resolve the terms behind a discount it gave (see
 * {@link Promotion#getDeletedAt()}), but it must never price another sale or appear in the
 * admin list — so there is deliberately no unfiltered lookup on this interface.
 */
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByDeletedAtIsNullOrderByActiveDescStartAtDescIdDesc();

    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByActiveTrueAndDeletedAtIsNullAndStartAtLessThanEqualAndEndAtGreaterThanEqualOrderByPriorityDescIdDesc(
            LocalDateTime startAt,
            LocalDateTime endAt
    );

    @EntityGraph(attributePaths = "targets")
    Optional<Promotion> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Every promotion that could price a sale, regardless of date window or branch.
     *
     * <p>This is what gets cached. Date and branch are filtered in memory on each call, because
     * a cached "active at time T" list is wrong the moment a start date passes, whereas the set
     * of switched-on, non-deleted promotions only changes when someone edits one — and every
     * edit evicts. Ordered by priority so ties resolve the same way they always have.
     */
    @EntityGraph(attributePaths = "targets")
    List<Promotion> findByActiveTrueAndDeletedAtIsNullOrderByPriorityDescIdDesc();

    /**
     * The caps and running counters for a set of promotions, read fresh at every checkout.
     *
     * <p>Not part of the cached snapshot on purpose: the counters move on every sale, and
     * evicting the whole tenant's promotion cache each time a promotion fires would throw away
     * the point of caching. The static configuration stays cached; this one indexed query gets
     * the moving part.
     *
     * <p>Columns: id, maxTotalRedemptions, maxRedemptionsPerCustomer, budgetAmount,
     * timesRedeemed, budgetConsumed, codeCount.
     */
    @Query(value = """
        SELECT p.id, p.max_total_redemptions, p.max_redemptions_per_customer, p.budget_amount,
               p.times_redeemed, p.budget_consumed,
               (SELECT COUNT(*) FROM promotion_codes c WHERE c.promotion_id = p.id) AS code_count
        FROM promotions p
        WHERE p.id IN (:ids)
        """, nativeQuery = true)
    List<Object[]> limitStateRaw(@Param("ids") List<Long> ids);

    /**
     * Counts a redemption against the promotion's caps, or refuses. Budget is checked on the
     * state before this order: a promotion stops applying once its budget is spent, and the
     * last sale is allowed to overshoot by at most its own discount rather than being refused
     * at the till for being Rs. 5 over. The redemption count is strict.
     */
    @Modifying
    @Query(value = """
        UPDATE promotions
        SET times_redeemed = times_redeemed + 1,
            budget_consumed = budget_consumed + :amount
        WHERE id = :id
          AND (max_total_redemptions IS NULL OR times_redeemed < max_total_redemptions)
          AND (budget_amount IS NULL OR budget_consumed < budget_amount)
        """, nativeQuery = true)
    int consume(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    /**
     * Counts a redemption that already happened. An offline till cannot see the caps, and the
     * customer has already paid and left, so the sale is recorded as it was made and the
     * counters catch up — even past the limit. The limit still stops new online sales.
     */
    @Modifying
    @Query(value = """
        UPDATE promotions
        SET times_redeemed = times_redeemed + 1,
            budget_consumed = budget_consumed + :amount
        WHERE id = :id
        """, nativeQuery = true)
    int consumeUnchecked(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    /** Gives a redemption and its budget back on refund, floored at zero. */
    @Modifying
    @Query(value = """
        UPDATE promotions
        SET times_redeemed = GREATEST(0, times_redeemed - 1),
            budget_consumed = GREATEST(0, budget_consumed - :amount)
        WHERE id = :id
        """, nativeQuery = true)
    int release(@Param("id") Long id, @Param("amount") java.math.BigDecimal amount);

    /**
     * Every promotion including retired ones, for the history page.
     *
     * <p>The one place {@code deletedAt} is deliberately not filtered — a campaign that was
     * deleted is exactly what history exists to still show.
     */
    @EntityGraph(attributePaths = "targets")
    List<Promotion> findAllByOrderByStartAtDescIdDesc();

    /**
     * What each promotion actually gave away, counting item-level and bill-level discounts
     * together.
     *
     * <p>The two halves live in different tables — {@code order_items.promotion_id} for a line
     * discount, {@code orders.bill_promotion_id} for a whole-order one — and RPT-08 reads only
     * the second, which is why item campaigns report as nothing there. Revenue is attributed
     * once per order in each half, so summing the halves cannot double-count an order that had
     * both.
     *
     * <p>Columns: promotionId, timesApplied, totalDiscountGiven, totalRevenue.
     */
    @Query(value = """
        SELECT promotion_id, SUM(times_applied), SUM(discount_given), SUM(revenue)
        FROM (
            SELECT oi.promotion_id                              AS promotion_id,
                   COUNT(DISTINCT oi.order_id)                  AS times_applied,
                   COALESCE(SUM(oi.promotion_discount_amount),0) AS discount_given,
                   COALESCE(SUM(oi.line_total), 0)              AS revenue
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE oi.promotion_id IS NOT NULL
              AND o.status = 'COMPLETED'
              AND (:branchId = 0 OR o.branch_id = :branchId)
              AND o.created_at BETWEEN :fromDate AND :toDate
            GROUP BY oi.promotion_id

            UNION ALL

            SELECT o.bill_promotion_id                              AS promotion_id,
                   COUNT(o.id)                                      AS times_applied,
                   COALESCE(SUM(o.bill_promotion_discount_amount),0) AS discount_given,
                   COALESCE(SUM(o.grand_total), 0)                  AS revenue
            FROM orders o
            WHERE o.bill_promotion_id IS NOT NULL
              AND o.status = 'COMPLETED'
              AND (:branchId = 0 OR o.branch_id = :branchId)
              AND o.created_at BETWEEN :fromDate AND :toDate
            GROUP BY o.bill_promotion_id
        ) combined
        GROUP BY promotion_id
        """, nativeQuery = true)
    List<Object[]> promotionTotalsRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    /**
     * The redemption ledger, line-level and bill-level in one list, newest first.
     *
     * <p>Columns: orderId, invoiceNo, soldAt, branchId, promotionId, promotionName, level,
     * itemId, itemName, discountAmount, orderTotal.
     */
    @Query(value = """
        SELECT * FROM (
            SELECT o.id            AS order_id,
                   o.invoice_no    AS invoice_no,
                   o.created_at    AS sold_at,
                   o.branch_id     AS branch_id,
                   oi.promotion_id AS promotion_id,
                   oi.promotion_name AS promotion_name,
                   'ITEM'          AS level,
                   oi.item_id      AS item_id,
                   oi.item_name    AS item_name,
                   oi.promotion_discount_amount AS discount_amount,
                   o.grand_total   AS order_total
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            WHERE oi.promotion_id IS NOT NULL
              AND o.status = 'COMPLETED'
              AND (:promotionId = 0 OR oi.promotion_id = :promotionId)
              AND (:branchId = 0 OR o.branch_id = :branchId)
              AND o.created_at BETWEEN :fromDate AND :toDate

            UNION ALL

            SELECT o.id, o.invoice_no, o.created_at, o.branch_id,
                   o.bill_promotion_id, o.bill_promotion_name,
                   'BILL', NULL, NULL,
                   o.bill_promotion_discount_amount, o.grand_total
            FROM orders o
            WHERE o.bill_promotion_id IS NOT NULL
              AND o.status = 'COMPLETED'
              AND (:promotionId = 0 OR o.bill_promotion_id = :promotionId)
              AND (:branchId = 0 OR o.branch_id = :branchId)
              AND o.created_at BETWEEN :fromDate AND :toDate
        ) redemptions
        ORDER BY sold_at DESC, order_id DESC
        LIMIT :pageSize OFFSET :pageOffset
        """, nativeQuery = true)
    List<Object[]> promotionRedemptionsRaw(
            @Param("promotionId") Long promotionId,
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("pageSize") int pageSize,
            @Param("pageOffset") int pageOffset);
}
