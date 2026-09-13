package com.chala.posapp.repository;

import com.chala.posapp.entity.CustomerSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CustomerSegmentRepository extends JpaRepository<CustomerSegment, Long> {

    List<CustomerSegment> findByActiveTrueOrderByNameAsc();

    List<CustomerSegment> findAllByOrderByNameAsc();

    /**
     * The customers a segment's rules currently pick out.
     *
     * <p>Thresholds arrive as sentinels rather than nulls, {@code -1} means "not a condition".
     * A native query comparing a bound null needs the parameter typed on every dialect, and a
     * sentinel keeps the SQL readable and the behaviour the same everywhere.
     *
     * <p>Aggregates come from a single grouped pass over completed orders, so this is one scan
     * rather than a subquery per customer. Customers with no orders survive the join and are
     * filtered by the thresholds, which is what makes an "inactive for N days" segment able to
     * include someone who has never bought at all.
     */
    @Query(value = """
        SELECT c.id
        FROM customers c
        LEFT JOIN (
            SELECT o.customer_id            AS customer_id,
                   SUM(o.grand_total)       AS spend,
                   COUNT(*)                 AS order_count,
                   MAX(o.created_at)        AS last_order_at
            FROM orders o
            WHERE o.status = 'COMPLETED' AND o.customer_id IS NOT NULL
            GROUP BY o.customer_id
        ) s ON s.customer_id = c.id
        WHERE c.active = TRUE
          AND c.deleted_at IS NULL
          AND (:minSpend  < 0 OR COALESCE(s.spend, 0) >= :minSpend)
          AND (:minOrders < 0 OR COALESCE(s.order_count, 0) >= :minOrders)
          AND (:minAov    < 0 OR (COALESCE(s.order_count, 0) > 0 AND s.spend / s.order_count >= :minAov))
          AND (:withinDays < 0 OR (s.last_order_at IS NOT NULL AND s.last_order_at >= :withinCutoff))
          AND (:inactiveDays < 0 OR (s.last_order_at IS NULL OR s.last_order_at < :inactiveCutoff))
        """, nativeQuery = true)
    List<Long> matchingCustomerIds(
            @Param("minSpend") java.math.BigDecimal minSpend,
            @Param("minOrders") int minOrders,
            @Param("minAov") java.math.BigDecimal minAov,
            @Param("withinDays") int withinDays,
            @Param("withinCutoff") LocalDateTime withinCutoff,
            @Param("inactiveDays") int inactiveDays,
            @Param("inactiveCutoff") LocalDateTime inactiveCutoff);
}
