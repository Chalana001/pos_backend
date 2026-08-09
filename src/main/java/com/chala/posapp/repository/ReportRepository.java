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
        SELECT c.name AS category_name, SUM(oi.line_total) AS total
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        JOIN items i ON i.id = oi.item_id
        JOIN sub_categories sc ON sc.id = i.sub_category_id
        JOIN categories c ON c.id = sc.category_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY c.name
    """, nativeQuery = true)
    List<Object[]> salesByCategoryRaw(
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
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
        SELECT COALESCE(SUM(total_return_amount), 0)
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
            COALESCE(SUM(ori.return_line_amount),0) AS total_return_amount
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
            COALESCE(SUM(total_return_amount),0) AS total_amount
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
            SELECT DATE(created_at) AS lbl, 'SALE' AS rtype, total_return_amount AS amount
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
            SELECT DATE_FORMAT(created_at, '%Y-%m') AS lbl, 'SALE' AS rtype, total_return_amount AS amount
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
                   SUM(refund_amount) AS totalRefunds
            FROM order_returns
            WHERE (:branchId = 0 OR branch_id = :branchId)
              AND created_at BETWEEN :fromDate AND :toDate
              AND status = 'APPROVED'
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
            COALESCE(i.cost_price, 0)                               AS costPrice,
            CASE
                WHEN i.item_type IN ('NORMAL','WEIGHT','VOLUME')
                THEN (COALESCE(SUM(sb.quantity), 0) / 1000.0) * COALESCE(i.cost_price, 0)
                ELSE COALESCE(SUM(sb.quantity), 0) * COALESCE(i.cost_price, 0)
            END                                                     AS stockValue,
            COALESCE(i.selling_price, 0)                            AS sellingPrice,
            CASE
                WHEN i.item_type IN ('NORMAL','WEIGHT','VOLUME')
                THEN (COALESCE(SUM(sb.quantity), 0) / 1000.0) * COALESCE(i.selling_price, 0)
                ELSE COALESCE(SUM(sb.quantity), 0) * COALESCE(i.selling_price, 0)
            END                                                     AS potentialRevenue
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
        GROUP BY i.id, i.barcode, i.name, c.name, sc.name,
                 i.item_type, i.default_unit, i.cost_price, i.selling_price
        ORDER BY stockValue DESC
    """, nativeQuery = true)
    List<Object[]> inventoryValuationRaw(
            @Param("branchId") Long branchId,
            @Param("categoryId") Long categoryId);

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
            u.username                                  AS createdByUsername
        FROM grn g
        JOIN suppliers s ON s.id = g.supplier_id
        JOIN branches b ON b.id = g.branch_id
        LEFT JOIN users u ON u.id = g.created_by_user_id
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
            COALESCE(SUM(g.total_amount), 0)    AS totalAmount,
            COALESCE(SUM(g.paid_amount), 0)     AS totalPaid,
            COALESCE(SUM(g.total_amount - g.paid_amount), 0) AS totalDue
        FROM grn g
        JOIN suppliers s ON s.id = g.supplier_id
        WHERE (:branchId = 0 OR g.branch_id = :branchId)
          AND g.received_at BETWEEN :fromDate AND :toDate
          AND (:supplierId = 0 OR s.id = :supplierId)
    """, nativeQuery = true)
    Object[] grnReportTotals(
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

            /* Opening stock = batches received BEFORE fromDate still on hand */
            COALESCE((
                SELECT SUM(sb2.original_quantity)
                FROM stock_batches sb2
                WHERE sb2.item_id = i.id
                  AND (:branchId = 0 OR sb2.branch_id = :branchId)
                  AND sb2.received_at < :fromDate
            ), 0)  AS openingStock,

            /* Purchases in (GRN items received in range) */
            COALESCE((
                SELECT SUM(gi.quantity)
                FROM grn_items gi
                JOIN grn g ON g.id = gi.grn_id
                WHERE gi.item_id = i.id
                  AND (:branchId = 0 OR g.branch_id = :branchId)
                  AND g.received_at BETWEEN :fromDate AND :toDate
            ), 0)  AS purchasesIn,

            /* Sales out */
            COALESCE((
                SELECT SUM(oi.quantity)
                FROM order_items oi
                JOIN orders ord ON ord.id = oi.order_id
                WHERE oi.item_id = i.id
                  AND (:branchId = 0 OR ord.branch_id = :branchId)
                  AND ord.status = 'COMPLETED'
                  AND ord.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS salesOut,

            /* Customer returns in */
            COALESCE((
                SELECT SUM(ori.quantity)
                FROM order_return_items ori
                JOIN order_returns orr ON orr.id = ori.order_return_id
                WHERE ori.item_id = i.id
                  AND (:branchId = 0 OR orr.branch_id = :branchId)
                  AND orr.status = 'APPROVED'
                  AND orr.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS returnsIn,

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
                SELECT SUM(sti.quantity)
                FROM stock_transfer_items sti
                JOIN stock_transfers st ON st.id = sti.transfer_id
                WHERE sti.item_id = i.id
                  AND (:branchId = 0 OR st.to_branch_id = :branchId)
                  AND st.status = 'COMPLETED'
                  AND st.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS transfersIn,

            /* Transfers out */
            COALESCE((
                SELECT SUM(sti2.quantity)
                FROM stock_transfer_items sti2
                JOIN stock_transfers st2 ON st2.id = sti2.transfer_id
                WHERE sti2.item_id = i.id
                  AND (:branchId = 0 OR st2.from_branch_id = :branchId)
                  AND st2.status = 'COMPLETED'
                  AND st2.created_at BETWEEN :fromDate AND :toDate
            ), 0)  AS transfersOut

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
                WHEN DATEDIFF(NOW(), o.created_at) <= 30 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket0to30,
            COALESCE(SUM(CASE
                WHEN DATEDIFF(NOW(), o.created_at) BETWEEN 31 AND 60 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket31to60,
            COALESCE(SUM(CASE
                WHEN DATEDIFF(NOW(), o.created_at) BETWEEN 61 AND 90 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket61to90,
            COALESCE(SUM(CASE
                WHEN DATEDIFF(NOW(), o.created_at) > 90 THEN o.due_amount ELSE 0
            END), 0)                                AS bucket91plus,
            MIN(o.created_at)                       AS oldestOrderAt
        FROM customers c
        JOIN orders o ON o.customer_id = c.id
        WHERE o.due_amount > 0
          AND o.status IN ('COMPLETED', 'CREDIT')
          AND c.active = true
          AND c.deleted_at IS NULL
        GROUP BY c.id, c.name, c.phone
        HAVING totalDue > 0
        ORDER BY totalDue DESC
    """, nativeQuery = true)
    List<Object[]> creditAgingRaw();

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
            COALESCE(SUM(CASE WHEN o.payment_type = 'CASH'   THEN o.grand_total ELSE 0 END), 0) AS cashSales,
            COALESCE(SUM(CASE WHEN o.payment_type = 'CREDIT' THEN o.grand_total ELSE 0 END), 0) AS creditSales,
            COALESCE(SUM(o.total_discount), 0)  AS totalDiscount,
            COUNT(o.id)                          AS orderCount
        FROM orders o
        WHERE o.branch_id       = :branchId
          AND o.cashier_user_id = :cashierUserId
          AND o.created_at BETWEEN :fromDate AND :toDate
          AND o.status IN ('COMPLETED', 'CREDIT')
    """, nativeQuery = true)
    Object[] shiftSalesRaw(
            @Param("branchId")       Long branchId,
            @Param("cashierUserId")  Long cashierUserId,
            @Param("fromDate")       LocalDateTime fromDate,
            @Param("toDate")         LocalDateTime toDate);
}
