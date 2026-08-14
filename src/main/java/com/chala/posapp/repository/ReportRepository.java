package com.chala.posapp.repository;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportRepository extends JpaRepository<Order, Long> {

    @Query(value = """
        SELECT 'HIGH_DISCOUNT', o.invoice_no, 'Manual bill discount at or above 20 percent', o.bill_discount, 'HIGH'
        FROM orders o WHERE (:branchId=0 OR o.branch_id=:branchId) AND o.status='COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate AND o.sub_total>0 AND (o.bill_discount/o.sub_total)*100>=20
        UNION ALL
        SELECT 'LARGE_RETURN', r.return_no, 'Completed refund at or above LKR 5,000', r.total_refund_amount, 'HIGH'
        FROM order_returns r WHERE (:branchId=0 OR r.branch_id=:branchId) AND r.status='COMPLETED'
          AND r.created_at BETWEEN :fromDate AND :toDate AND r.total_refund_amount>=5000
        UNION ALL
        SELECT 'CASH_SHORTAGE', CONCAT('Shift #',s.id), 'Closed shift cash shortage at or above LKR 500', ABS(s.cash_difference), 'CRITICAL'
        FROM cash_shifts s WHERE (:branchId=0 OR s.branch_id=:branchId) AND s.status='CLOSED'
          AND s.closed_at BETWEEN :fromDate AND :toDate AND s.cash_difference<=-500
        UNION ALL
        SELECT 'STALE_SHIFT', CONCAT('Shift #',s2.id), 'Open shift older than 12 hours', 0, 'HIGH'
        FROM cash_shifts s2 WHERE (:branchId=0 OR s2.branch_id=:branchId) AND s2.status='OPEN'
          AND s2.opened_at < :staleBefore
        UNION ALL
        SELECT 'LARGE_ADJUSTMENT', i.name, 'Stock adjustment at or above 100 display units', ABS(sa.display_qty_change), 'HIGH'
        FROM stock_adjustments sa JOIN items i ON i.id=sa.item_id
        WHERE (:branchId=0 OR sa.branch_id=:branchId) AND sa.created_at BETWEEN :fromDate AND :toDate
          AND ABS(sa.display_qty_change)>=100
        """, nativeQuery = true)
    List<Object[]> exceptionActivityRaw(@Param("branchId") Long branchId,
                                        @Param("fromDate") LocalDateTime fromDate,
                                        @Param("toDate") LocalDateTime toDate,
                                        @Param("staleBefore") LocalDateTime staleBefore);

    @Query(value = """
        SELECT b.id, b.name,
          COUNT(o.id), COALESCE(SUM(o.grand_total),0), COALESCE(AVG(o.grand_total),0), COALESCE(SUM(o.bill_discount),0),
          COALESCE((SELECT COUNT(*) FROM order_returns r WHERE r.branch_id=b.id AND r.status='COMPLETED' AND r.created_at BETWEEN :fromDate AND :toDate),0),
          COALESCE((SELECT SUM(r2.total_refund_amount) FROM order_returns r2 WHERE r2.branch_id=b.id AND r2.status='COMPLETED' AND r2.created_at BETWEEN :fromDate AND :toDate),0),
          COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.branch_id=b.id AND e.count_in_profit_report=true AND e.created_at BETWEEN :fromDate AND :toDate),0)
        FROM branches b
        LEFT JOIN orders o ON o.branch_id=b.id AND o.status='COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate
        WHERE b.active=true
        GROUP BY b.id,b.name
        ORDER BY COALESCE(SUM(o.grand_total),0) DESC
        """, nativeQuery = true)
    List<Object[]> branchComparisonRaw(@Param("fromDate") LocalDateTime fromDate,
                                       @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT c.id, c.name, c.phone,
          MIN(o.created_at) AS firstPurchaseAt,
          MAX(o.created_at) AS lastPurchaseAt,
          SUM(CASE WHEN o.created_at BETWEEN :fromDate AND :toDate THEN 1 ELSE 0 END) AS periodOrders,
          COUNT(o.id) AS lifetimeOrders,
          COALESCE(SUM(CASE WHEN o.created_at BETWEEN :fromDate AND :toDate THEN o.grand_total ELSE 0 END), 0) AS periodSpend,
          COALESCE(SUM(o.grand_total), 0) AS lifetimeSpend,
          c.due_amount
        FROM customers c
        JOIN orders o ON o.customer_id = c.id
        WHERE o.status = 'COMPLETED'
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.created_at <= :toDate
          AND c.deleted_at IS NULL
        GROUP BY c.id, c.name, c.phone, c.due_amount
        HAVING SUM(CASE WHEN o.created_at BETWEEN :fromDate AND :toDate THEN 1 ELSE 0 END) > 0
        ORDER BY periodSpend DESC
        """, nativeQuery = true)
    List<Object[]> customerBehaviorRaw(@Param("branchId") Long branchId,
                                       @Param("fromDate") LocalDateTime fromDate,
                                       @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT i.id, i.barcode, i.name, i.default_unit, i.item_type,
          COALESCE((SELECT SUM(sb.quantity) FROM stock_batches sb WHERE sb.item_id = i.id AND (:branchId = 0 OR sb.branch_id = :branchId)), 0),
          i.reorder_level, COALESCE(i.cost_price, 0), COALESCE(i.selling_price, 0),
          COALESCE((SELECT SUM(oi.qty) FROM order_items oi JOIN orders o ON o.id = oi.order_id
            WHERE oi.item_id = i.id AND (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED'
              AND o.created_at >= :salesFrom), 0),
          (SELECT MAX(o2.created_at) FROM order_items oi2 JOIN orders o2 ON o2.id = oi2.order_id
            WHERE oi2.item_id = i.id AND (:branchId = 0 OR o2.branch_id = :branchId) AND o2.status = 'COMPLETED'),
          (SELECT s.name FROM supplier_items si JOIN suppliers s ON s.id = si.supplier_id
            WHERE si.item_id = i.id ORDER BY si.primary_supplier DESC, si.id ASC LIMIT 1),
          (SELECT MIN(sb2.expire_date) FROM stock_batches sb2 WHERE sb2.item_id = i.id
            AND (:branchId = 0 OR sb2.branch_id = :branchId) AND sb2.quantity > 0 AND sb2.expire_date IS NOT NULL)
        FROM items i
        WHERE i.active = true AND i.deleted_at IS NULL
          AND (:branchId = 0 OR EXISTS (SELECT 1 FROM stock_batches scoped_sb WHERE scoped_sb.item_id = i.id AND scoped_sb.branch_id = :branchId)
               OR EXISTS (SELECT 1 FROM order_items scoped_oi JOIN orders scoped_o ON scoped_o.id = scoped_oi.order_id
                          WHERE scoped_oi.item_id = i.id AND scoped_o.branch_id = :branchId))
        ORDER BY i.name
        """, nativeQuery = true)
    List<Object[]> stockHealthRaw(@Param("branchId") Long branchId,
                                  @Param("salesFrom") LocalDateTime salesFrom);

    @Query(value = """
        SELECT i.id, i.barcode, i.name, i.default_unit, i.item_type,
          COALESCE((SELECT SUM(sb.quantity) FROM stock_batches sb WHERE sb.item_id = i.id AND (:branchId = 0 OR sb.branch_id = :branchId)), 0),
          COALESCE(i.cost_price, 0), COALESCE(i.selling_price, 0), i.reorder_level,
          COALESCE(SUM(CASE WHEN o.created_at >= :recentFrom THEN CASE WHEN i.item_type IN ('RECIPE','SERVICE') THEN COALESCE(oi.display_qty, oi.qty) ELSE oi.qty END ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN o.created_at >= :historyFrom AND o.created_at < :recentFrom THEN CASE WHEN i.item_type IN ('RECIPE','SERVICE') THEN COALESCE(oi.display_qty, oi.qty) ELSE oi.qty END ELSE 0 END), 0),
          COUNT(DISTINCT CASE WHEN o.created_at >= :historyFrom THEN DATE(o.created_at) END)
        FROM items i
        LEFT JOIN order_items oi ON oi.item_id = i.id
        LEFT JOIN orders o ON o.id = oi.order_id AND o.status = 'COMPLETED'
          AND (:branchId = 0 OR o.branch_id = :branchId) AND o.created_at >= :historyFrom
        WHERE i.active = true AND i.deleted_at IS NULL AND i.item_type <> 'SERVICE'
          AND (:branchId = 0 OR EXISTS (SELECT 1 FROM stock_batches scoped_sb WHERE scoped_sb.item_id = i.id AND scoped_sb.branch_id = :branchId)
               OR EXISTS (SELECT 1 FROM order_items scoped_oi JOIN orders scoped_o ON scoped_o.id = scoped_oi.order_id
                          WHERE scoped_oi.item_id = i.id AND scoped_o.branch_id = :branchId))
          AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM sub_categories forecast_sc
               WHERE forecast_sc.id = i.sub_category_id AND forecast_sc.category_id = :categoryId))
          AND (:subCategoryId IS NULL OR i.sub_category_id = :subCategoryId)
          AND (:supplierId IS NULL OR EXISTS (SELECT 1 FROM supplier_items forecast_si
               WHERE forecast_si.item_id = i.id AND forecast_si.supplier_id = :supplierId))
        GROUP BY i.id, i.barcode, i.name, i.default_unit, i.item_type, i.cost_price, i.selling_price, i.reorder_level
        ORDER BY i.name
        """, nativeQuery = true)
    List<Object[]> demandForecastRaw(@Param("branchId") Long branchId,
                                     @Param("historyFrom") LocalDateTime historyFrom,
                                     @Param("recentFrom") LocalDateTime recentFrom,
                                     @Param("categoryId") Long categoryId,
                                     @Param("subCategoryId") Long subCategoryId,
                                     @Param("supplierId") Long supplierId);

    @Query(value = """
        SELECT oi.item_id, DATE(o.created_at), SUM(CASE WHEN COALESCE(oi.item_type, i.item_type) IN ('RECIPE','SERVICE') THEN COALESCE(oi.display_qty, oi.qty) ELSE oi.qty END)
        FROM order_items oi JOIN orders o ON o.id = oi.order_id LEFT JOIN items i ON i.id = oi.item_id
        WHERE o.status = 'COMPLETED' AND (:branchId = 0 OR o.branch_id = :branchId) AND o.created_at >= :historyFrom
        GROUP BY oi.item_id, DATE(o.created_at)
        """, nativeQuery = true)
    List<Object[]> dailyItemDemandRaw(@Param("branchId") Long branchId,
                                      @Param("historyFrom") LocalDateTime historyFrom);

    @Query(value = """
        SELECT oi.item_id, COALESCE(SUM(CASE WHEN COALESCE(oi.item_type, i.item_type) IN ('NORMAL','WEIGHT','VOLUME') THEN oi.qty / 1000.0 ELSE oi.qty END), 0)
        FROM order_items oi JOIN orders o ON o.id = oi.order_id LEFT JOIN items i ON i.id = oi.item_id
        WHERE o.status = 'COMPLETED' AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.created_at >= :fromDate AND o.created_at < :toDate
        GROUP BY oi.item_id
        """, nativeQuery = true)
    List<Object[]> itemDemandBetweenRaw(@Param("branchId") Long branchId,
                                        @Param("fromDate") LocalDateTime fromDate,
                                        @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT
          COALESCE((SELECT SUM(oi.line_total) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(o.bill_discount) FROM orders o WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(ori.refund_line_amount) FROM order_return_items ori JOIN order_returns r ON r.id = ori.order_return_id WHERE (:branchId = 0 OR r.branch_id = :branchId) AND r.status = 'COMPLETED' AND r.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(oi.line_cost) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(CASE WHEN oi.qty > 0 THEN oi.line_cost * ori.return_qty / oi.qty ELSE 0 END) FROM order_return_items ori JOIN order_returns r ON r.id = ori.order_return_id JOIN order_items oi ON oi.id = ori.order_item_id WHERE (:branchId = 0 OR r.branch_id = :branchId) AND r.status = 'COMPLETED' AND r.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE (:branchId = 0 OR e.branch_id = :branchId) AND e.count_in_profit_report = true AND e.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND oi.line_total > 0 AND o.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND oi.line_total > 0 AND COALESCE(oi.line_cost, 0) <= 0 AND o.created_at BETWEEN :fromDate AND :toDate), 0)
        """, nativeQuery = true)
    List<Object[]> profitAndLossRaw(@Param("branchId") Long branchId,
                                    @Param("fromDate") LocalDateTime fromDate,
                                    @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT movement_date, SUM(inflow) AS inflow, SUM(outflow) AS outflow
        FROM (
            SELECT DATE(o.created_at) movement_date,
                   SUM(COALESCE(o.sale_paid_amount, CASE WHEN o.order_type = 'CASH' THEN LEAST(o.paid_amount, o.grand_total) ELSE 0 END)) inflow,
                   0 outflow
            FROM orders o
            WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED'
              AND o.created_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(o.created_at)
            UNION ALL
            SELECT DATE(cp.paid_at), SUM(cp.amount), 0
            FROM credit_payments cp
            WHERE (:branchId = 0 OR cp.branch_id = :branchId) AND UPPER(cp.payment_method) = 'CASH'
              AND cp.paid_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(cp.paid_at)
            UNION ALL
            SELECT DATE(e.created_at), 0, SUM(e.amount)
            FROM expenses e
            WHERE (:branchId = 0 OR e.branch_id = :branchId)
              AND e.created_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(e.created_at)
            UNION ALL
            SELECT DATE(p.created_at), 0, SUM(p.cash_source_amount)
            FROM purchase p
            WHERE (:branchId = 0 OR p.cash_source_branch_id = :branchId) AND p.status = 'COMPLETED'
              AND p.cash_source IN ('BRANCH_CASH','CASH_DRAWER')
              AND p.created_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(p.created_at)
            UNION ALL
            SELECT DATE(sp.paid_at), 0, SUM(sp.amount)
            FROM supplier_payments sp
            WHERE (:branchId = 0 OR sp.cash_source_branch_id = :branchId)
              AND sp.cash_source IN ('BRANCH_CASH','CASH_DRAWER')
              AND sp.paid_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(sp.paid_at)
            UNION ALL
            SELECT DATE(r.created_at), 0, SUM(r.total_refund_amount)
            FROM order_returns r
            WHERE (:branchId = 0 OR r.branch_id = :branchId) AND r.status = 'COMPLETED'
              AND UPPER(r.refund_method) = 'CASH'
              AND r.created_at BETWEEN :fromDate AND :toDate
            GROUP BY DATE(r.created_at)
        ) movements
        GROUP BY movement_date
        ORDER BY movement_date
        """, nativeQuery = true)
    List<Object[]> cashFlowDailyRaw(@Param("branchId") Long branchId,
                                    @Param("fromDate") LocalDateTime fromDate,
                                    @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT
          COALESCE((SELECT SUM(COALESCE(o.sale_paid_amount, CASE WHEN o.order_type = 'CASH' THEN LEAST(o.paid_amount, o.grand_total) ELSE 0 END)) FROM orders o WHERE (:branchId = 0 OR o.branch_id = :branchId) AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(cp.amount) FROM credit_payments cp WHERE (:branchId = 0 OR cp.branch_id = :branchId) AND UPPER(cp.payment_method) = 'CASH' AND cp.paid_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE (:branchId = 0 OR e.branch_id = :branchId) AND e.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(p.cash_source_amount) FROM purchase p WHERE (:branchId = 0 OR p.cash_source_branch_id = :branchId) AND p.status = 'COMPLETED' AND p.cash_source IN ('BRANCH_CASH','CASH_DRAWER') AND p.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(sp.amount) FROM supplier_payments sp WHERE (:branchId = 0 OR sp.cash_source_branch_id = :branchId) AND sp.cash_source IN ('BRANCH_CASH','CASH_DRAWER') AND sp.paid_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(r.total_refund_amount) FROM order_returns r WHERE (:branchId = 0 OR r.branch_id = :branchId) AND r.status = 'COMPLETED' AND UPPER(r.refund_method) = 'CASH' AND r.created_at BETWEEN :fromDate AND :toDate), 0),
          COALESCE((SELECT SUM(cd.amount) FROM cash_drops cd WHERE (:branchId = 0 OR cd.branch_id = :branchId) AND cd.created_at BETWEEN :fromDate AND :toDate), 0)
        """, nativeQuery = true)
    List<Object[]> cashFlowTotalsRaw(@Param("branchId") Long branchId,
                                     @Param("fromDate") LocalDateTime fromDate,
                                     @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(
            COALESCE(
                o.sale_paid_amount,
                CASE
                    WHEN o.order_type = 'CASH' THEN
                        CASE
                            WHEN o.paid_amount < o.grand_total THEN o.paid_amount
                            ELSE o.grand_total
                        END
                    ELSE 0
                END
            )
        ),0) FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(
            COALESCE(
                o.sale_due_amount,
                CASE
                    WHEN o.order_type = 'CREDIT' THEN o.grand_total
                    ELSE 0
                END
            )
        ),0) FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0) FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalDiscount(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COUNT(*) FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long totalOrders(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);


    /**
     * BUG-06 FIX: Unified product performance query — replaces the two previously
     * duplicated queries (topSellingRaw + productPerformanceRaw) that shared an
     * identical inner subquery. Both callers now go through this single method.
     *
     * Columns returned: [0]=item_id [1]=item_name [2]=item_type [3]=qty_unit
     *                   [4]=qty_sold [5]=revenue [6]=cost [7]=profit
     *
     * @param sortBy        REVENUE | QUANTITY | PROFIT
     * @param sortDirection ASC | DESC
     * @param limitValue    max rows to return
     * @param offsetValue   0 for top-N queries, page*size for paginated queries
     */
    @Query(value = """
        SELECT *
        FROM (
            SELECT
                item_id,
                item_name,
                item_type,
                qty_unit,
                CASE
                    WHEN item_type = 'NORMAL' THEN base_qty / 1000.0
                    WHEN item_type = 'WEIGHT' AND qty_unit = 'KG' THEN base_qty / 1000.0
                    WHEN item_type = 'VOLUME' AND qty_unit = 'L' THEN base_qty / 1000.0
                    ELSE base_qty
                END AS qty_sold,
                revenue,
                cost,
                profit
            FROM (
                SELECT
                    oi.item_id,
                    oi.item_name,
                    COALESCE(oi.item_type, i.item_type) AS item_type,
                    COALESCE(i.default_unit, oi.qty_unit) AS qty_unit,
                    COALESCE(SUM(oi.qty), 0)                       AS base_qty,
                    COALESCE(SUM(oi.line_total), 0)                AS revenue,
                    COALESCE(SUM(oi.line_cost), 0)                 AS cost,
                    COALESCE(SUM(oi.line_total - oi.line_cost), 0) AS profit
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                LEFT JOIN items i ON i.id = oi.item_id
                WHERE (:branchId = 0 OR o.branch_id = :branchId)
                  AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
                  AND o.status = 'COMPLETED'
                  AND o.created_at BETWEEN :fromDate AND :toDate
                GROUP BY oi.item_id, oi.item_name,
                         COALESCE(oi.item_type, i.item_type),
                         COALESCE(i.default_unit, oi.qty_unit)
            ) product_performance_grouped
        ) product_performance
        ORDER BY
            CASE WHEN :sortDirection = 'ASC'  AND :sortBy = 'QUANTITY' THEN product_performance.qty_sold END ASC,
            CASE WHEN :sortDirection = 'ASC'  AND :sortBy = 'PROFIT'   THEN product_performance.profit   END ASC,
            CASE WHEN :sortDirection = 'ASC'  AND :sortBy = 'REVENUE'  THEN product_performance.revenue  END ASC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'QUANTITY' THEN product_performance.qty_sold END DESC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'PROFIT'   THEN product_performance.profit   END DESC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'REVENUE'  THEN product_performance.revenue  END DESC,
            product_performance.revenue DESC
        LIMIT :limitValue OFFSET :offsetValue
    """, nativeQuery = true)
    List<Object[]> productPerformanceRaw(
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue,
            @Param("offsetValue") int offsetValue
    );

    @Query(value = """
        SELECT COUNT(*)
        FROM (
            SELECT oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type), COALESCE(i.default_unit, oi.qty_unit)
            FROM order_items oi
            JOIN orders o ON o.id = oi.order_id
            LEFT JOIN items i ON i.id = oi.item_id
            WHERE (:branchId = 0 OR o.branch_id = :branchId)
              AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
              AND o.status = 'COMPLETED'
              AND o.created_at BETWEEN :fromDate AND :toDate
            GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type), COALESCE(i.default_unit, oi.qty_unit)
        ) product_count
    """, nativeQuery = true)
    long countProductPerformance(
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
        SELECT
            oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type) AS item_type,
            COALESCE(SUM(oi.display_qty),0) AS qty_sold,
            COALESCE(SUM(oi.line_total),0) AS revenue,
            COALESCE(SUM(oi.line_cost),0) AS cost,
            COALESCE(SUM(oi.line_total - oi.line_cost),0) AS profit
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        LEFT JOIN items i ON i.id = oi.item_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type)
        ORDER BY profit DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> profitReportRaw(
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue
    );

    /**
     * BUG-05 FIX: Single-row aggregate query for profit summary.
     * Returns: [0]=totalRevenue, [1]=totalCost, [2]=grossProfit
     * Previously getProfitSummary() was loading up to 1,000,000 rows into a
     * Java List and summing them in a for-loop — this replaces that with one DB call.
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(oi.line_total), 0)               AS total_revenue,
            COALESCE(SUM(oi.line_cost), 0)                AS total_cost,
            COALESCE(SUM(oi.line_total - oi.line_cost), 0) AS gross_profit
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    ProfitSummaryProjection profitSummaryRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
        SELECT CASE WHEN :singleCategory = true THEN sc.name ELSE c.name END AS category_name, SUM(oi.line_total) AS total
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        JOIN items i ON i.id = oi.item_id
        JOIN sub_categories sc ON sc.id = i.sub_category_id
        JOIN categories c ON c.id = sc.category_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY CASE WHEN :singleCategory = true THEN sc.name ELSE c.name END
    """, nativeQuery = true)
    List<Object[]> salesByCategoryRaw(
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("singleCategory") boolean singleCategory,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT o.id, o.invoice_no, o.grand_total, o.order_type, o.created_at
    FROM orders o
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
    ORDER BY o.created_at DESC
    LIMIT 10
""", nativeQuery = true)
    List<Object[]> recentOrdersRaw(@Param("branchId") Long branchId);

    // PERF-06 FIX: Added fromDate/toDate filter — previously scanned ALL historical orders
    // on every call (grows exponentially with data). Also upgraded casts to toLong/toDouble in caller.
    @Query(value = """
    SELECT
        c.id,
        c.name,
        c.phone,
        COUNT(o.id) as order_count,
        COALESCE(SUM(o.grand_total), 0) as total_spent
    FROM orders o
    JOIN customers c ON c.id = o.customer_id
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    GROUP BY c.id, c.name, c.phone
    ORDER BY total_spent DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> topCustomersRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue
    );

    @Query(value = """
    SELECT
        c.id,
        c.name,
        c.phone,
        COUNT(o.id) AS order_count,
        COALESCE(SUM(o.grand_total), 0) AS total_spent,
        COALESCE(SUM(o.paid_amount), 0) AS total_paid,
        COALESCE(SUM(o.due_amount), 0) AS total_due,
        COALESCE(AVG(o.grand_total), 0) AS average_order_value,
        MAX(o.created_at) AS last_order_at
    FROM orders o
    JOIN customers c ON c.id = o.customer_id
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    GROUP BY c.id, c.name, c.phone
    ORDER BY
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'TOTAL_SPENT' THEN total_spent END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'ORDER_COUNT' THEN order_count END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'TOTAL_DUE' THEN total_due END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'AVG_ORDER' THEN average_order_value END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'LAST_ORDER' THEN last_order_at END ASC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'TOTAL_SPENT' THEN total_spent END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'ORDER_COUNT' THEN order_count END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'TOTAL_DUE' THEN total_due END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'AVG_ORDER' THEN average_order_value END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'LAST_ORDER' THEN last_order_at END DESC,
        total_spent DESC
    LIMIT :limitValue OFFSET :offsetValue
""", nativeQuery = true)
    List<Object[]> customerPerformanceRaw(
            @Param("branchId") Long branchId,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue,
            @Param("offsetValue") int offsetValue
    );

    @Query(value = """
    SELECT COUNT(DISTINCT c.id)
    FROM orders o
    JOIN customers c ON c.id = o.customer_id
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countCustomerPerformance(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    // PERF-07 FIX: Added branchId + date range filters — previously a full unfiltered table scan
    // on every call with no branch isolation. Switched to grn table (has branch_id + received_at)
    // matching the supplier performance query pattern already used in supplierPerformanceRaw.
    @Query(value = """
    SELECT
        s.id,
        s.name,
        s.phone,
        COUNT(g.id) as purchase_count,
        COALESCE(SUM(g.total_amount), 0) as total_purchased
    FROM grn g
    JOIN suppliers s ON s.id = g.supplier_id
    WHERE (:branchId = 0 OR g.branch_id = :branchId)
      AND g.received_at BETWEEN :fromDate AND :toDate
    GROUP BY s.id, s.name, s.phone
    ORDER BY total_purchased DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> topSuppliersRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue
    );

    @Query(value = """
    SELECT
        s.id,
        s.name,
        s.phone,
        COUNT(g.id) AS purchase_count,
        COALESCE(SUM(g.total_amount), 0) AS total_purchased,
        COALESCE(SUM(g.paid_amount), 0) AS total_paid,
        COALESCE(SUM(COALESCE(g.total_amount, 0) - COALESCE(g.paid_amount, 0)), 0) AS total_due,
        COALESCE(AVG(g.total_amount), 0) AS average_purchase_value,
        MAX(g.received_at) AS last_purchase_at
    FROM grn g
    JOIN suppliers s ON s.id = g.supplier_id
    WHERE (:branchId = 0 OR g.branch_id = :branchId)
      AND g.received_at BETWEEN :fromDate AND :toDate
    GROUP BY s.id, s.name, s.phone
    ORDER BY
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'TOTAL_PURCHASED' THEN total_purchased END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'PURCHASE_COUNT' THEN purchase_count END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'TOTAL_DUE' THEN total_due END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'AVG_PURCHASE' THEN average_purchase_value END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'LAST_PURCHASE' THEN last_purchase_at END ASC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'TOTAL_PURCHASED' THEN total_purchased END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'PURCHASE_COUNT' THEN purchase_count END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'TOTAL_DUE' THEN total_due END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'AVG_PURCHASE' THEN average_purchase_value END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'LAST_PURCHASE' THEN last_purchase_at END DESC,
        total_purchased DESC
    LIMIT :limitValue OFFSET :offsetValue
""", nativeQuery = true)
    List<Object[]> supplierPerformanceRaw(
            @Param("branchId") Long branchId,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue,
            @Param("offsetValue") int offsetValue
    );

    @Query(value = """
    SELECT COUNT(DISTINCT s.id)
    FROM grn g
    JOIN suppliers s ON s.id = g.supplier_id
    WHERE (:branchId = 0 OR g.branch_id = :branchId)
      AND g.received_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countSupplierPerformance(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT
        o.id,
        o.invoice_no,
        o.branch_id,
        o.receipt_branch_name,
        o.customer_id,
        COALESCE(c.name, 'Walk-in Customer') AS customer_name,
        c.phone AS customer_phone,
        o.cashier_user_id,
        u.username AS cashier_name,
        o.order_type,
        o.payment_method,
        o.sale_mode,
        o.status,
        o.sub_total,
        o.bill_discount,
        o.grand_total,
        o.paid_amount,
        o.due_amount,
        o.created_at
    FROM orders o
    LEFT JOIN customers c ON c.id = o.customer_id
    LEFT JOIN users u ON u.id = o.cashier_user_id
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND (:orderType IS NULL OR o.order_type = :orderType)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    ORDER BY
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'DATE' THEN o.created_at END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'TOTAL' THEN o.grand_total END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'PAID' THEN o.paid_amount END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'DUE' THEN o.due_amount END ASC,
        CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'DISCOUNT' THEN o.bill_discount END ASC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'DATE' THEN o.created_at END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'TOTAL' THEN o.grand_total END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'PAID' THEN o.paid_amount END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'DUE' THEN o.due_amount END DESC,
        CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'DISCOUNT' THEN o.bill_discount END DESC,
        o.created_at DESC
    LIMIT :limitValue OFFSET :offsetValue
""", nativeQuery = true)
    List<Object[]> salesReportRaw(
            @Param("branchId") Long branchId,
            @Param("orderType") String orderType,
            @Param("sortBy") String sortBy,
            @Param("sortDirection") String sortDirection,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue,
            @Param("offsetValue") int offsetValue
    );

    @Query(value = """
    SELECT COUNT(*)
    FROM orders o
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND (:orderType IS NULL OR o.order_type = :orderType)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countSalesReport(
            @Param("branchId") Long branchId,
            @Param("orderType") String orderType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT COALESCE(SUM(amount), 0)
    FROM expenses
    WHERE (:branchId = 0 OR branch_id = :branchId)
      AND COALESCE(count_in_profit_report, TRUE) = TRUE
      AND created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    double getTotalExpenses(@Param("branchId") Long branchId,
                            @Param("fromDate") LocalDateTime fromDate,
                            @Param("toDate") LocalDateTime toDate);


    @Query(value = """
        SELECT DATE(o.created_at) AS sales_date, COALESCE(SUM(o.grand_total),0) AS sales
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE(o.created_at)
        ORDER BY sales_date
    """, nativeQuery = true)
    List<Object[]> dailySalesRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
        SELECT DATE_FORMAT(o.created_at, '%Y-%m') AS sales_month, COALESCE(SUM(o.grand_total),0) AS sales
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE_FORMAT(o.created_at, '%Y-%m')
        ORDER BY sales_month
    """, nativeQuery = true)
    List<Object[]> monthlySalesRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    // ─── Returns Report Queries ───────────────────────────────────────────

    @Query(value = """
        SELECT COALESCE(COUNT(*), 0)
        FROM order_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long saleReturnCount(@Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(total_refund_amount), 0)
        FROM order_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double saleReturnTotal(@Param("branchId") Long branchId,
                           @Param("fromDate") LocalDateTime fromDate,
                           @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(ori.return_qty), 0)
        FROM order_return_items ori
        JOIN order_returns orr ON orr.id = ori.order_return_id
        WHERE (:branchId = 0 OR orr.branch_id = :branchId)
          AND orr.status = 'COMPLETED'
          AND orr.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long saleReturnItemCount(@Param("branchId") Long branchId,
                             @Param("fromDate") LocalDateTime fromDate,
                             @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(COUNT(*), 0)
        FROM purchase_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long purchaseReturnCount(@Param("branchId") Long branchId,
                             @Param("fromDate") LocalDateTime fromDate,
                             @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(total_return_amount), 0)
        FROM purchase_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double purchaseReturnTotal(@Param("branchId") Long branchId,
                               @Param("fromDate") LocalDateTime fromDate,
                               @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(pri.return_qty), 0)
        FROM purchase_return_items pri
        JOIN purchase_returns pr ON pr.id = pri.purchase_return_id
        WHERE (:branchId = 0 OR pr.branch_id = :branchId)
          AND pr.status = 'COMPLETED'
          AND pr.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long purchaseReturnItemCount(@Param("branchId") Long branchId,
                                 @Param("fromDate") LocalDateTime fromDate,
                                 @Param("toDate") LocalDateTime toDate);

    // Top returned items (sale returns)
    @Query(value = """
        SELECT
            ori.item_id,
            ori.item_name,
            ori.barcode,
            COUNT(DISTINCT orr.id)          AS return_count,
            COALESCE(SUM(ori.return_qty),0) AS total_returned_qty,
            COALESCE(SUM(ori.refund_line_amount),0) AS total_return_amount
        FROM order_return_items ori
        JOIN order_returns orr ON orr.id = ori.order_return_id
        WHERE (:branchId = 0 OR orr.branch_id = :branchId)
          AND orr.status = 'COMPLETED'
          AND orr.created_at BETWEEN :fromDate AND :toDate
        GROUP BY ori.item_id, ori.item_name, ori.barcode
        ORDER BY total_returned_qty DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topReturnedSaleItemsRaw(@Param("branchId") Long branchId,
                                           @Param("fromDate") LocalDateTime fromDate,
                                           @Param("toDate") LocalDateTime toDate,
                                           @Param("limitValue") int limitValue);

    // Top returned items (purchase returns)
    @Query(value = """
        SELECT
            pri.item_id,
            pri.item_name,
            pri.barcode,
            COUNT(DISTINCT pr.id)           AS return_count,
            COALESCE(SUM(pri.return_qty),0) AS total_returned_qty,
            COALESCE(SUM(pri.return_line_amount),0) AS total_return_amount
        FROM purchase_return_items pri
        JOIN purchase_returns pr ON pr.id = pri.purchase_return_id
        WHERE (:branchId = 0 OR pr.branch_id = :branchId)
          AND pr.status = 'COMPLETED'
          AND pr.created_at BETWEEN :fromDate AND :toDate
        GROUP BY pri.item_id, pri.item_name, pri.barcode
        ORDER BY total_returned_qty DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topReturnedPurchaseItemsRaw(@Param("branchId") Long branchId,
                                               @Param("fromDate") LocalDateTime fromDate,
                                               @Param("toDate") LocalDateTime toDate,
                                               @Param("limitValue") int limitValue);

    // Return reason breakdown — sale returns
    @Query(value = """
        SELECT
            reason,
            COUNT(*) AS cnt,
            COALESCE(SUM(total_refund_amount),0) AS total_amount
        FROM order_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
        GROUP BY reason
        ORDER BY cnt DESC
        LIMIT 20
    """, nativeQuery = true)
    List<Object[]> saleReturnReasonBreakdownRaw(@Param("branchId") Long branchId,
                                                @Param("fromDate") LocalDateTime fromDate,
                                                @Param("toDate") LocalDateTime toDate);

    // Return reason breakdown — purchase returns
    @Query(value = """
        SELECT
            reason,
            COUNT(*) AS cnt,
            COALESCE(SUM(total_return_amount),0) AS total_amount
        FROM purchase_returns
        WHERE (:branchId = 0 OR branch_id = :branchId)
          AND status = 'COMPLETED'
          AND created_at BETWEEN :fromDate AND :toDate
        GROUP BY reason
        ORDER BY cnt DESC
        LIMIT 20
    """, nativeQuery = true)
    List<Object[]> purchaseReturnReasonBreakdownRaw(@Param("branchId") Long branchId,
                                                    @Param("fromDate") LocalDateTime fromDate,
                                                    @Param("toDate") LocalDateTime toDate);

    // Daily return trend
    @Query(value = """
        SELECT
            lbl AS label,
            COALESCE(SUM(CASE WHEN rtype = 'SALE' THEN amount ELSE 0 END), 0)     AS sale_returns,
            COALESCE(SUM(CASE WHEN rtype = 'PURCHASE' THEN amount ELSE 0 END), 0) AS purchase_returns
        FROM (
            SELECT DATE(created_at) AS lbl, 'SALE' AS rtype, total_refund_amount AS amount
            FROM order_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND status = 'COMPLETED'
              AND created_at BETWEEN :fromDate AND :toDate
            UNION ALL
            SELECT DATE(created_at) AS lbl, 'PURCHASE' AS rtype, total_return_amount AS amount
            FROM purchase_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND status = 'COMPLETED'
              AND created_at BETWEEN :fromDate AND :toDate
        ) combined
        GROUP BY lbl
        ORDER BY lbl
    """, nativeQuery = true)
    List<Object[]> dailyReturnTrendRaw(@Param("branchId") Long branchId,
                                       @Param("fromDate") LocalDateTime fromDate,
                                       @Param("toDate") LocalDateTime toDate);

    // Monthly return trend
    @Query(value = """
        SELECT
            lbl AS label,
            COALESCE(SUM(CASE WHEN rtype = 'SALE' THEN amount ELSE 0 END), 0)     AS sale_returns,
            COALESCE(SUM(CASE WHEN rtype = 'PURCHASE' THEN amount ELSE 0 END), 0) AS purchase_returns
        FROM (
            SELECT DATE_FORMAT(created_at, '%Y-%m') AS lbl, 'SALE' AS rtype, total_refund_amount AS amount
            FROM order_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND status = 'COMPLETED'
              AND created_at BETWEEN :fromDate AND :toDate
            UNION ALL
            SELECT DATE_FORMAT(created_at, '%Y-%m') AS lbl, 'PURCHASE' AS rtype, total_return_amount AS amount
            FROM purchase_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND status = 'COMPLETED'
              AND created_at BETWEEN :fromDate AND :toDate
        ) combined
        GROUP BY lbl
        ORDER BY lbl
    """, nativeQuery = true)
    List<Object[]> monthlyReturnTrendRaw(@Param("branchId") Long branchId,
                                         @Param("fromDate") LocalDateTime fromDate,
                                         @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-01: Cashier Performance
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            o.cashier_user_id                               AS cashierUserId,
            u.username                                      AS cashierUsername,
            COUNT(o.id)                                     AS orderCount,
            COALESCE(SUM(o.grand_total), 0)                 AS totalSales,
            COALESCE(SUM(o.bill_discount + o.promotion_discount_total), 0) AS totalDiscounts,
            COALESCE(AVG(o.grand_total), 0)                 AS avgOrderValue,
            COALESCE(r.returnCount, 0)                      AS returnCount,
            COALESCE(r.totalRefunds, 0)                     AS totalRefunds
        FROM orders o
        JOIN users u ON u.id = o.cashier_user_id
        LEFT JOIN (
            SELECT cashier_user_id,
                   COUNT(*)    AS returnCount,
                   SUM(total_refund_amount) AS totalRefunds
            FROM order_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND created_at BETWEEN :fromDate AND :toDate
               AND status = 'COMPLETED'
            GROUP BY cashier_user_id
        ) r ON r.cashier_user_id = o.cashier_user_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY o.cashier_user_id, u.username, r.returnCount, r.totalRefunds
        ORDER BY totalSales DESC
    """, nativeQuery = true)
    List<Object[]> cashierPerformanceRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-02: Inventory Valuation
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            i.id                                                    AS itemId,
            i.barcode,
            i.name                                                  AS itemName,
            c.name                                                  AS categoryName,
            sc.name                                                 AS subCategoryName,
            i.item_type                                             AS itemType,
            i.default_unit                                          AS unit,
            CASE
                WHEN i.item_type IN ('NORMAL','WEIGHT','VOLUME')
                THEN COALESCE(SUM(sb.quantity), 0) / 1000.0
                ELSE COALESCE(SUM(sb.quantity), 0)
            END                                                     AS qtyOnHand,
            CASE
                WHEN COALESCE(SUM(sb.quantity), 0) > 0
                THEN COALESCE(SUM(sb.quantity * sb.cost_price), 0) / SUM(sb.quantity)
                ELSE COALESCE(i.cost_price, 0)
            END                                                     AS costPrice,
            CASE
                WHEN i.item_type IN ('NORMAL','WEIGHT','VOLUME')
                THEN COALESCE(SUM(sb.quantity * sb.cost_price), 0) / 1000.0
                ELSE COALESCE(SUM(sb.quantity * sb.cost_price), 0)
            END                                                     AS stockValue,
            COALESCE(i.selling_price, 0)                            AS sellingPrice,
            CASE
                WHEN i.item_type IN ('NORMAL','WEIGHT','VOLUME')
                THEN (COALESCE(SUM(sb.quantity), 0) / 1000.0) * COALESCE(i.selling_price, 0)
                ELSE COALESCE(SUM(sb.quantity), 0) * COALESCE(i.selling_price, 0)
            END                                                     AS potentialRevenue,
            i.pos_visible                                           AS posVisible
        FROM items i
        LEFT JOIN stock_batches sb
               ON sb.item_id = i.id
              AND (:branchId = 0 OR sb.branch_id = :branchId)
        LEFT JOIN sub_categories sc ON sc.id = i.sub_category_id
        LEFT JOIN categories c ON c.id = sc.category_id
        WHERE i.item_type != 'SERVICE'
          AND i.active = true
          AND i.deleted_at IS NULL
          AND (:categoryId = 0 OR c.id = :categoryId)
          AND (:subCategoryId = 0 OR sc.id = :subCategoryId)
        GROUP BY i.id, i.barcode, i.name, c.name, sc.name,
                 i.item_type, i.default_unit, i.cost_price, i.selling_price, i.pos_visible
        ORDER BY stockValue DESC
    """, nativeQuery = true)
    List<Object[]> inventoryValuationRaw(
            @Param("branchId") Long branchId,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-04: GRN / Purchase Report
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            g.id                                        AS grnId,
            g.grn_no                                    AS grnNo,
            s.id                                        AS supplierId,
            s.name                                      AS supplierName,
            b.id                                        AS branchId,
            b.name                                      AS branchName,
            COALESCE(g.total_amount, 0)                 AS totalAmount,
            COALESCE(g.paid_amount, 0)                  AS paidAmount,
            COALESCE(g.total_amount, 0) - COALESCE(g.paid_amount, 0) AS dueAmount,
            g.note,
            g.received_at                               AS receivedAt,
            u.username                                  AS createdByUsername,
            p.id                                        AS purchaseId,
            p.invoice_no                                AS purchaseInvoiceNo,
            CAST(p.status AS CHAR)                      AS purchaseStatus,
            COALESCE(p.paid_amount, 0)                  AS purchasePaidAmount,
            COALESCE(p.due_amount, 0)                   AS purchaseDueAmount,
            COALESCE((SELECT SUM(pr.total_return_amount) FROM purchase_returns pr
                      WHERE pr.grn_id = g.id AND pr.status = 'COMPLETED'), 0) AS returnAmount
        FROM grn g
        JOIN suppliers s ON s.id = g.supplier_id
        JOIN branches b ON b.id = g.branch_id
        LEFT JOIN users u ON u.id = g.created_by_user_id
        LEFT JOIN purchase p ON p.id = g.purchase_id
        WHERE (:branchId = 0 OR g.branch_id = :branchId)
          AND g.received_at BETWEEN :fromDate AND :toDate
          AND (:supplierId = 0 OR s.id = :supplierId)
        ORDER BY g.received_at DESC
        LIMIT :limitValue OFFSET :offsetValue
    """, nativeQuery = true)
    List<Object[]> grnReportRaw(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limit,
            @Param("offsetValue") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM grn g
        JOIN suppliers s ON s.id = g.supplier_id
        WHERE (:branchId = 0 OR g.branch_id = :branchId)
          AND g.received_at BETWEEN :fromDate AND :toDate
          AND (:supplierId = 0 OR s.id = :supplierId)
    """, nativeQuery = true)
    long countGrnReport(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT
            COALESCE(SUM(g.total_amount), 0) AS totalAmount,
            COALESCE((SELECT SUM(p2.paid_amount) FROM purchase p2 WHERE p2.id IN (
                SELECT DISTINCT g2.purchase_id FROM grn g2 JOIN suppliers s2 ON s2.id = g2.supplier_id
                WHERE (:branchId = 0 OR g2.branch_id = :branchId) AND g2.received_at BETWEEN :fromDate AND :toDate
                  AND (:supplierId = 0 OR s2.id = :supplierId))), 0) AS totalPaid,
            COALESCE((SELECT SUM(p3.due_amount) FROM purchase p3 WHERE p3.id IN (
                SELECT DISTINCT g3.purchase_id FROM grn g3 JOIN suppliers s3 ON s3.id = g3.supplier_id
                WHERE (:branchId = 0 OR g3.branch_id = :branchId) AND g3.received_at BETWEEN :fromDate AND :toDate
                  AND (:supplierId = 0 OR s3.id = :supplierId))), 0) AS totalDue,
            COALESCE((SELECT SUM(pr.total_return_amount) FROM purchase_returns pr
                WHERE pr.status = 'COMPLETED' AND pr.grn_id IN (
                    SELECT g4.id FROM grn g4 JOIN suppliers s4 ON s4.id = g4.supplier_id
                    WHERE (:branchId = 0 OR g4.branch_id = :branchId) AND g4.received_at BETWEEN :fromDate AND :toDate
                      AND (:supplierId = 0 OR s4.id = :supplierId))), 0) AS totalReturns,
            COUNT(DISTINCT g.purchase_id) AS uniquePurchaseCount
        FROM grn g
        JOIN suppliers s ON s.id = g.supplier_id
        WHERE (:branchId = 0 OR g.branch_id = :branchId)
          AND g.received_at BETWEEN :fromDate AND :toDate
          AND (:supplierId = 0 OR s.id = :supplierId)
    """, nativeQuery = true)
    List<Object[]> grnReportTotals(
            @Param("branchId") Long branchId,
            @Param("supplierId") Long supplierId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-05: Stock Movement
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            i.id          AS itemId,
            i.barcode,
            i.name        AS itemName,
            i.default_unit AS unit,
            i.item_type   AS itemType,

            /* Opening stock = current stock reversed by all movements since fromDate. */
            COALESCE((
                SELECT SUM(sb2.quantity)
                FROM stock_batches sb2
                WHERE sb2.item_id = i.id
                  AND (:branchId = 0 OR sb2.branch_id = :branchId)
            ), 0)
            - COALESCE((
                SELECT SUM(gi2.qty)
                FROM grn_items gi2
                JOIN grn g2 ON g2.id = gi2.grn_id
                WHERE gi2.item_id = i.id
                  AND (:branchId = 0 OR g2.branch_id = :branchId)
                  AND g2.received_at >= :fromDate
            ), 0)
            + COALESCE((
                SELECT SUM(oi2.qty)
                FROM order_items oi2
                JOIN orders ord2 ON ord2.id = oi2.order_id
                WHERE oi2.item_id = i.id
                  AND (:branchId = 0 OR ord2.branch_id = :branchId)
                  AND ord2.status = 'COMPLETED'
                  AND ord2.created_at >= :fromDate
            ), 0)
            - COALESCE((
                SELECT SUM(ori2.return_qty)
                FROM order_return_items ori2
                JOIN order_returns orr2 ON orr2.id = ori2.order_return_id
                WHERE ori2.item_id = i.id
                  AND ori2.stock_reversed = true
                  AND (:branchId = 0 OR orr2.branch_id = :branchId)
                  AND orr2.status = 'COMPLETED'
                  AND orr2.created_at >= :fromDate
            ), 0)
            + COALESCE((
                SELECT SUM(pri2.return_qty)
                FROM purchase_return_items pri2
                JOIN purchase_returns pr2 ON pr2.id = pri2.purchase_return_id
                WHERE pri2.item_id = i.id
                  AND pri2.stock_deducted = true
                  AND (:branchId = 0 OR pr2.branch_id = :branchId)
                  AND pr2.status = 'COMPLETED'
                  AND pr2.created_at >= :fromDate
            ), 0)
            - COALESCE((
                SELECT SUM(sa2.qty_change)
                FROM stock_adjustments sa2
                WHERE sa2.item_id = i.id
                  AND (:branchId = 0 OR sa2.branch_id = :branchId)
                  AND sa2.created_at >= :fromDate
            ), 0)
            - COALESCE((
                SELECT SUM(sti3.qty)
                FROM stock_transfer_items sti3
                JOIN stock_transfers st3 ON st3.id = sti3.transfer_id
                WHERE sti3.item_id = i.id
                  AND (:branchId = 0 OR st3.to_branch_id = :branchId)
                  AND st3.status = 'COMPLETED'
                  AND st3.received_at >= :fromDate
            ), 0)
            + COALESCE((
                SELECT SUM(sti4.qty)
                FROM stock_transfer_items sti4
                JOIN stock_transfers st4 ON st4.id = sti4.transfer_id
                WHERE sti4.item_id = i.id
                  AND (:branchId = 0 OR st4.from_branch_id = :branchId)
                  AND st4.status IN ('IN_TRANSIT', 'COMPLETED')
                  AND st4.requested_at >= :fromDate
            ), 0)
            + COALESCE((SELECT SUM(sp2.source_qty) FROM stock_processings sp2
                WHERE sp2.source_item_id = i.id AND sp2.processing_status = 'COMPLETED' AND (:branchId = 0 OR sp2.branch_id = :branchId)
                AND sp2.processed_at >= :fromDate), 0)
            - COALESCE((SELECT SUM(spo2.quantity) FROM stock_processing_outputs spo2
                JOIN stock_processings sp3 ON sp3.id = spo2.processing_id
                WHERE spo2.output_item_id = i.id AND spo2.is_waste = false AND sp3.processing_status = 'COMPLETED'
                AND (:branchId = 0 OR sp3.branch_id = :branchId) AND sp3.processed_at >= :fromDate), 0) AS openingStock,

            /* Purchases in (GRN items received in range) */
            COALESCE((
                SELECT SUM(gi.qty)
                FROM grn_items gi
                JOIN grn g ON g.id = gi.grn_id
                WHERE gi.item_id = i.id
                  AND (:branchId = 0 OR g.branch_id = :branchId)
                  AND g.received_at BETWEEN :fromDate AND :toDate
            ), 0)  AS purchasesIn,

            /* Sales out */
            COALESCE((
                SELECT SUM(oi.qty)
                FROM order_items oi
                JOIN orders ord ON ord.id = oi.order_id
                WHERE oi.item_id = i.id
                  AND (:branchId = 0 OR ord.branch_id = :branchId)
                  AND ord.status = 'COMPLETED'
                  AND ord.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS salesOut,

            /* Customer returns in */
            COALESCE((
                SELECT SUM(ori.return_qty)
                FROM order_return_items ori
                JOIN order_returns orr ON orr.id = ori.order_return_id
                WHERE ori.item_id = i.id
                  AND (:branchId = 0 OR orr.branch_id = :branchId)
                  AND ori.stock_reversed = true
                  AND orr.status = 'COMPLETED'
                  AND orr.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS returnsIn,

            /* Purchase returns out */
            COALESCE((
                SELECT SUM(pri.return_qty)
                FROM purchase_return_items pri
                JOIN purchase_returns pr ON pr.id = pri.purchase_return_id
                WHERE pri.item_id = i.id
                  AND pri.stock_deducted = true
                  AND (:branchId = 0 OR pr.branch_id = :branchId)
                  AND pr.status = 'COMPLETED'
                  AND pr.created_at BETWEEN :fromDate AND :toDate
            ), 0) AS purchaseReturnsOut,

            /* Net adjustments */
            COALESCE((
                SELECT SUM(sa.qty_change)
                FROM stock_adjustments sa
                WHERE sa.item_id = i.id
                  AND (:branchId = 0 OR sa.branch_id = :branchId)
                  AND sa.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS adjustmentsNet,

            /* Transfers in */
            COALESCE((
                SELECT SUM(sti.qty)
                FROM stock_transfer_items sti
                JOIN stock_transfers st ON st.id = sti.transfer_id
                WHERE sti.item_id = i.id
                  AND (:branchId = 0 OR st.to_branch_id = :branchId)
                  AND st.status = 'COMPLETED'
                  AND st.received_at BETWEEN :fromDate AND :toDate
            ), 0)  AS transfersIn,

            /* Transfers out */
            COALESCE((
                SELECT SUM(sti2.qty)
                FROM stock_transfer_items sti2
                JOIN stock_transfers st2 ON st2.id = sti2.transfer_id
                WHERE sti2.item_id = i.id
                  AND (:branchId = 0 OR st2.from_branch_id = :branchId)
                  AND st2.status IN ('IN_TRANSIT', 'COMPLETED')
                  AND st2.requested_at BETWEEN :fromDate AND :toDate
            ), 0)  AS transfersOut,

            COALESCE((SELECT SUM(spo.quantity) FROM stock_processing_outputs spo
                JOIN stock_processings sp ON sp.id = spo.processing_id
                WHERE spo.output_item_id = i.id AND spo.is_waste = false AND sp.processing_status = 'COMPLETED'
                AND (:branchId = 0 OR sp.branch_id = :branchId)
                AND sp.processed_at BETWEEN :fromDate AND :toDate), 0) AS processingIn,

            COALESCE((SELECT SUM(sp.source_qty) FROM stock_processings sp
                WHERE sp.source_item_id = i.id AND sp.processing_status = 'COMPLETED' AND (:branchId = 0 OR sp.branch_id = :branchId)
                AND sp.processed_at BETWEEN :fromDate AND :toDate), 0) AS processingOut

        FROM items i
        WHERE i.item_type != 'SERVICE'
          AND i.active = true
          AND i.deleted_at IS NULL
        ORDER BY i.name ASC
        LIMIT :limitValue OFFSET :offsetValue
    """, nativeQuery = true)
    List<Object[]> stockMovementRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limit,
            @Param("offsetValue") int offset);

    @Query(value = """
        SELECT COUNT(*) FROM items i
        WHERE i.item_type != 'SERVICE' AND i.active = true AND i.deleted_at IS NULL
    """, nativeQuery = true)
    long countStockMovement();

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-06: Expense Report by Category
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            e.category                      AS category,
            e.expense_type_id               AS expenseTypeId,
            COUNT(e.id)                     AS cnt,
            COALESCE(SUM(e.amount), 0)      AS totalAmount,
            COALESCE(AVG(e.amount), 0)      AS avgAmount
        FROM expenses e
        WHERE (:branchId = 0 OR e.branch_id = :branchId)
          AND e.created_at BETWEEN :fromDate AND :toDate
        GROUP BY e.category, e.expense_type_id
        ORDER BY totalAmount DESC
    """, nativeQuery = true)
    List<Object[]> expensesByCategoryRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(e.amount), 0), COUNT(e.id)
        FROM expenses e
        WHERE (:branchId = 0 OR e.branch_id = :branchId)
          AND e.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    Object[] expenseTotalsRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-07: Customer Credit Aging
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            c.id                                    AS customerId,
            c.name                                  AS customerName,
            c.phone,
            COALESCE(SUM(o.due_amount), 0)          AS totalDue,
            COALESCE(SUM(CASE
                WHEN TIMESTAMPDIFF(DAY, o.created_at, CURRENT_TIMESTAMP) <= 30 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket0to30,
            COALESCE(SUM(CASE
                WHEN TIMESTAMPDIFF(DAY, o.created_at, CURRENT_TIMESTAMP) BETWEEN 31 AND 60 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket31to60,
            COALESCE(SUM(CASE
                WHEN TIMESTAMPDIFF(DAY, o.created_at, CURRENT_TIMESTAMP) BETWEEN 61 AND 90 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket61to90,
            COALESCE(SUM(CASE
                WHEN TIMESTAMPDIFF(DAY, o.created_at, CURRENT_TIMESTAMP) > 90 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket91plus,
            MIN(o.created_at)                       AS oldestOrderAt,
            (SELECT o2.invoice_no FROM orders o2 WHERE o2.customer_id = c.id
             AND (:branchId = 0 OR o2.branch_id = :branchId) AND o2.due_amount > 0
             AND o2.status = 'COMPLETED' ORDER BY o2.created_at ASC LIMIT 1) AS oldestInvoiceNo,
            COUNT(o.id)                             AS unpaidInvoiceCount,
            (SELECT MAX(cp.paid_at) FROM credit_payments cp WHERE cp.customer_id = c.id) AS lastPaymentAt,
            c.credit_limit                          AS creditLimit
        FROM customers c
        JOIN orders o ON o.customer_id = c.id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.due_amount > 0
          AND o.status = 'COMPLETED'
          AND c.active = true
          AND c.deleted_at IS NULL
        GROUP BY c.id, c.name, c.phone, c.credit_limit
        HAVING totalDue > 0
        ORDER BY totalDue DESC
    """, nativeQuery = true)
    List<Object[]> creditAgingRaw(@Param("branchId") Long branchId);

    @Query(value = """
        SELECT
            s.id, s.name, s.phone,
            COALESCE(SUM(p.due_amount), 0) AS totalDue,
            COALESCE(SUM(CASE WHEN TIMESTAMPDIFF(DAY, p.created_at, CURRENT_TIMESTAMP) <= 30 THEN p.due_amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN TIMESTAMPDIFF(DAY, p.created_at, CURRENT_TIMESTAMP) BETWEEN 31 AND 60 THEN p.due_amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN TIMESTAMPDIFF(DAY, p.created_at, CURRENT_TIMESTAMP) BETWEEN 61 AND 90 THEN p.due_amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN TIMESTAMPDIFF(DAY, p.created_at, CURRENT_TIMESTAMP) > 90 THEN p.due_amount ELSE 0 END), 0),
            MIN(p.created_at),
            (SELECT p2.invoice_no FROM purchase p2 WHERE p2.supplier_id = s.id
             AND p2.due_amount > 0 AND p2.status = 'COMPLETED'
             AND (:branchId = 0 OR EXISTS (SELECT 1 FROM grn g2 WHERE g2.purchase_id = p2.id AND g2.branch_id = :branchId))
             ORDER BY p2.created_at ASC LIMIT 1),
            COUNT(p.id),
            (SELECT MAX(sp.paid_at) FROM supplier_payments sp WHERE sp.supplier_id = s.id)
        FROM suppliers s
        JOIN purchase p ON p.supplier_id = s.id
        WHERE p.due_amount > 0 AND p.status = 'COMPLETED'
          AND s.active = true AND s.deleted_at IS NULL
          AND (:branchId = 0 OR EXISTS (SELECT 1 FROM grn g WHERE g.purchase_id = p.id AND g.branch_id = :branchId))
        GROUP BY s.id, s.name, s.phone
        HAVING COALESCE(SUM(p.due_amount), 0) > 0
        ORDER BY totalDue DESC
        """, nativeQuery = true)
    List<Object[]> supplierPayablesAgingRaw(@Param("branchId") Long branchId);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-08: Promotion Effectiveness
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            o.bill_promotion_id                             AS promotionId,
            o.bill_promotion_name                           AS promotionName,
            p.discount_type                                 AS discountType,
            p.discount_value                                AS discountValue,
            COUNT(o.id)                                     AS timesApplied,
            COALESCE(SUM(o.bill_promotion_discount_amount), 0) AS totalDiscountGiven,
            COALESCE(SUM(o.grand_total), 0)                 AS totalRevenue,
            COALESCE(AVG(o.grand_total), 0)                 AS avgOrderValue
        FROM orders o
        LEFT JOIN promotions p ON p.id = o.bill_promotion_id
        WHERE o.bill_promotion_id IS NOT NULL
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY o.bill_promotion_id, o.bill_promotion_name, p.discount_type, p.discount_value
        ORDER BY totalDiscountGiven DESC
    """, nativeQuery = true)
    List<Object[]> promotionEffectivenessRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-09: Warranty Report
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            w.item_id                                               AS itemId,
            w.item_name                                             AS itemName,
            w.barcode,
            COUNT(w.id)                                             AS totalWarranties,
            SUM(CASE WHEN w.status = 'ACTIVE'  THEN 1 ELSE 0 END)  AS activeCount,
            SUM(CASE WHEN w.status = 'CLAIMED' THEN 1 ELSE 0 END)  AS claimedCount,
            SUM(CASE WHEN w.status = 'EXPIRED' THEN 1 ELSE 0 END)  AS expiredCount,
            SUM(CASE WHEN w.status = 'VOID'    THEN 1 ELSE 0 END)  AS voidCount
        FROM warranties w
        WHERE (:branchId = 0 OR w.branch_id = :branchId)
          AND w.created_at BETWEEN :fromDate AND :toDate
        GROUP BY w.item_id, w.item_name, w.barcode
        ORDER BY claimedCount DESC, totalWarranties DESC
    """, nativeQuery = true)
    List<Object[]> warrantyReportRaw(
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    // ═══════════════════════════════════════════════════════════════════════════
    // RPT-03 helper: per-shift sales totals (cash sales, credit sales, discount, order count)
    // ═══════════════════════════════════════════════════════════════════════════

    @Query(value = """
        SELECT
            COALESCE(SUM(COALESCE(
                o.sale_paid_amount,
                CASE WHEN o.order_type = 'CASH' THEN LEAST(o.paid_amount, o.grand_total) ELSE 0 END
            )), 0) AS cashSales,
            COALESCE(SUM(COALESCE(
                o.sale_due_amount,
                CASE WHEN o.order_type = 'CREDIT' THEN o.due_amount ELSE 0 END
            )), 0) AS creditSales,
            COALESCE(SUM(o.bill_discount), 0) AS totalDiscount,
            COUNT(o.id)                          AS orderCount
        FROM orders o
        WHERE o.branch_id       = :branchId
          AND o.cashier_user_id = :cashierUserId
          AND o.created_at BETWEEN :fromDate AND :toDate
          AND o.status = 'COMPLETED'
    """, nativeQuery = true)
    List<Object[]> shiftSalesRaw(
            @Param("branchId")       Long branchId,
            @Param("cashierUserId")  Long cashierUserId,
            @Param("fromDate")       LocalDateTime fromDate,
            @Param("toDate")         LocalDateTime toDate);
}
