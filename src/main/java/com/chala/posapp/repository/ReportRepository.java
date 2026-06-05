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
        WHERE o.tenant_id = :tenantId 
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalSales(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

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
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

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
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0) FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalDiscount(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COUNT(*) FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long totalOrders(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);


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
            oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type) AS item_type,
            COALESCE(i.default_unit, oi.qty_unit) AS qty_unit,
            COALESCE(SUM(oi.qty),0) AS base_qty,
            COALESCE(SUM(oi.line_total),0) AS revenue,
            COALESCE(SUM(oi.line_cost),0) AS cost,
            COALESCE(SUM(oi.line_total - oi.line_cost),0) AS profit
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        LEFT JOIN items i ON i.id = oi.item_id
        WHERE o.tenant_id = :tenantId 
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type), COALESCE(i.default_unit, oi.qty_unit)
        ) product_performance_grouped
        ) product_performance
        ORDER BY
            CASE WHEN :rankBy = 'QUANTITY' THEN product_performance.qty_sold END DESC,
            CASE WHEN :rankBy = 'PROFIT' THEN product_performance.profit END DESC,
            CASE WHEN :rankBy = 'REVENUE' THEN product_performance.revenue END DESC,
            product_performance.revenue DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topSellingRaw(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("rankBy") String rankBy,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue
    );

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
            COALESCE(SUM(oi.qty),0) AS base_qty,
            COALESCE(SUM(oi.line_total),0) AS revenue,
            COALESCE(SUM(oi.line_cost),0) AS cost,
            COALESCE(SUM(oi.line_total - oi.line_cost),0) AS profit
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        LEFT JOIN items i ON i.id = oi.item_id
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type), COALESCE(i.default_unit, oi.qty_unit)
        ) product_performance_grouped
        ) product_performance
        ORDER BY
            CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'QUANTITY' THEN product_performance.qty_sold END ASC,
            CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'PROFIT' THEN product_performance.profit END ASC,
            CASE WHEN :sortDirection = 'ASC' AND :sortBy = 'REVENUE' THEN product_performance.revenue END ASC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'QUANTITY' THEN product_performance.qty_sold END DESC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'PROFIT' THEN product_performance.profit END DESC,
            CASE WHEN :sortDirection = 'DESC' AND :sortBy = 'REVENUE' THEN product_performance.revenue END DESC,
            product_performance.revenue DESC
        LIMIT :limitValue OFFSET :offsetValue
    """, nativeQuery = true)
    List<Object[]> productPerformanceRaw(
            @Param("tenantId") String tenantId,
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
            WHERE o.tenant_id = :tenantId
              AND (:branchId = 0 OR o.branch_id = :branchId)
              AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
              AND o.status = 'COMPLETED'
              AND o.created_at BETWEEN :fromDate AND :toDate
            GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type), COALESCE(i.default_unit, oi.qty_unit)
        ) product_count
    """, nativeQuery = true)
    long countProductPerformance(
            @Param("tenantId") String tenantId,
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
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name, COALESCE(oi.item_type, i.item_type)
        ORDER BY profit DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> profitReportRaw(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("limitValue") int limitValue
    );

    @Query(value = """
        SELECT DATE(o.created_at) AS date, COALESCE(SUM(o.grand_total),0) AS sales, COUNT(o.id) AS orders
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND o.status = 'COMPLETED'
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE(o.created_at)
        ORDER BY DATE(o.created_at)
    """, nativeQuery = true)
    List<Object[]> salesTrendRaw(
            @Param("tenantId") String tenantId,
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
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND (:itemType IS NULL OR COALESCE(oi.item_type, i.item_type) = :itemType)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY c.name
    """, nativeQuery = true)
    List<Object[]> salesByCategoryRaw(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("itemType") String itemType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT o.id, o.invoice_no, o.grand_total, o.order_type, o.created_at
    FROM orders o
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
    ORDER BY o.created_at DESC
    LIMIT 10
""", nativeQuery = true)
    List<Object[]> recentOrdersRaw(@Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    @Query(value = """
    SELECT 
        c.id, 
        c.name, 
        c.phone, 
        COUNT(o.id) as order_count, 
        COALESCE(SUM(o.grand_total), 0) as total_spent
    FROM orders o
    JOIN customers c ON c.id = o.customer_id
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
    GROUP BY c.id, c.name, c.phone
    ORDER BY total_spent DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> topCustomersRaw(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("limitValue") int limitValue);

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
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
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
            @Param("tenantId") String tenantId,
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
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countCustomerPerformance(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT 
        s.id, 
        s.name, 
        s.phone, 
        COUNT(p.id) as purchase_count, 
        COALESCE(SUM(p.grand_total), 0) as total_purchased
    FROM purchase p
    JOIN suppliers s ON s.id = p.supplier_id
    WHERE p.tenant_id = :tenantId
    GROUP BY s.id, s.name, s.phone
    ORDER BY total_purchased DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> topSuppliersRaw(@Param("tenantId") String tenantId, @Param("limitValue") int limitValue);

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
    WHERE g.tenant_id = :tenantId
      AND (:branchId = 0 OR g.branch_id = :branchId)
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
            @Param("tenantId") String tenantId,
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
    WHERE g.tenant_id = :tenantId
      AND (:branchId = 0 OR g.branch_id = :branchId)
      AND g.received_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countSupplierPerformance(
            @Param("tenantId") String tenantId,
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
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
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
            @Param("tenantId") String tenantId,
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
    WHERE o.tenant_id = :tenantId
      AND (:branchId = 0 OR o.branch_id = :branchId)
      AND (:orderType IS NULL OR o.order_type = :orderType)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    long countSalesReport(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("orderType") String orderType,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
    SELECT COALESCE(SUM(amount), 0)
    FROM expenses
    WHERE tenant_id = :tenantId
      AND (:branchId = 0 OR branch_id = :branchId)
      AND COALESCE(count_in_profit_report, TRUE) = TRUE
      AND created_at BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    double getTotalExpenses(@Param("tenantId") String tenantId,
                            @Param("branchId") Long branchId,
                            @Param("fromDate") LocalDateTime fromDate,
                            @Param("toDate") LocalDateTime toDate);


    @Query(value = """
        SELECT DATE(o.created_at) AS sales_date, COALESCE(SUM(o.grand_total),0) AS sales
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE(o.created_at)
        ORDER BY sales_date
    """, nativeQuery = true)
    List<Object[]> dailySalesRaw(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );

    @Query(value = """
        SELECT DATE_FORMAT(o.created_at, '%Y-%m') AS sales_month, COALESCE(SUM(o.grand_total),0) AS sales
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE_FORMAT(o.created_at, '%Y-%m')
        ORDER BY sales_month
    """, nativeQuery = true)
    List<Object[]> monthlySalesRaw(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate
    );
}
