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
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED' AND o.order_type = 'CASH'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED' AND o.order_type = 'CREDIT'
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
                WHEN item_type = 'WEIGHT' AND qty_unit = 'KG' THEN base_qty / 1000.0
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
    SELECT COALESCE(SUM(amount), 0)
    FROM expenses
    WHERE tenant_id = :tenantId
      AND (:branchId = 0 OR branch_id = :branchId)
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
