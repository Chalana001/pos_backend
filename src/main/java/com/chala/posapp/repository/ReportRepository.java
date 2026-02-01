package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportRepository extends JpaRepository<Order, Long> {

    // --- BASIC CARDS ---

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED' AND o.order_type = 'CASH'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0) FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED' AND o.order_type = 'CREDIT'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0) FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalDiscount(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COUNT(*) FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long totalOrders(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // --- LISTS & TABLES ---

    @Query(value = """
        SELECT 
            oi.item_id, oi.item_name,
            COALESCE(SUM(oi.qty),0) AS qty_sold,
            COALESCE(SUM(oi.line_total),0) AS revenue
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name
        ORDER BY qty_sold DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topSellingRaw(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate, @Param("limitValue") int limitValue);

    // ✅ FIXED: Using 'oi.cost_price' instead of 'i.cost_price'
    @Query(value = """
        SELECT
            oi.item_id, oi.item_name,
            COALESCE(SUM(oi.qty),0) AS qty_sold,
            COALESCE(SUM(oi.line_total),0) AS revenue,
            COALESCE(SUM(oi.qty * oi.cost_price),0) AS cost, 
            COALESCE(SUM(oi.line_total - (oi.qty * oi.cost_price)),0) AS profit
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name
        ORDER BY profit DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> profitReportRaw(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate, @Param("limitValue") int limitValue);

    // --- CHARTS ---

    // 1. Line Chart (Sales Trend)
    @Query(value = """
        SELECT DATE(o.created_at) AS date, COALESCE(SUM(o.grand_total),0) AS sales, COUNT(o.id) AS orders
        FROM orders o
        WHERE o.status = 'COMPLETED'
          AND (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY DATE(o.created_at)
        ORDER BY DATE(o.created_at)
    """, nativeQuery = true)
    List<Object[]> salesTrendRaw(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // 2. Pie Chart (Sales by Category) 🔥 NEW
    @Query(value = """
        SELECT c.name AS category_name, SUM(oi.line_total) AS total
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        JOIN items i ON i.id = oi.item_id
        JOIN sub_categories sc ON sc.id = i.sub_category_id
        JOIN categories c ON c.id = sc.category_id
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY c.name
    """, nativeQuery = true)
    List<Object[]> salesByCategoryRaw(@Param("branchId") Long branchId, @Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // 3. Recent Transactions Table 🔥 NEW
    @Query(value = """
        SELECT o.id, o.invoice_no, o.grand_total, o.order_type, o.created_at
        FROM orders o
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
        ORDER BY o.created_at DESC
        LIMIT 10
    """, nativeQuery = true)
    List<Object[]> recentOrdersRaw(@Param("branchId") Long branchId);

    @Query(value = """
        SELECT 
            c.id, 
            c.name, 
            c.phone, 
            COUNT(o.id) as order_count, 
            COALESCE(SUM(o.grand_total), 0) as total_spent
        FROM orders o
        JOIN customers c ON c.id = o.customer_id
        WHERE (:branchId IS NULL OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
        GROUP BY c.id, c.name, c.phone
        ORDER BY total_spent DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topCustomersRaw(@Param("branchId") Long branchId, @Param("limitValue") int limitValue);

    // 🔥 2. TOP SUPPLIERS (By Purchase Volume)
    // (Purchase Table එකක් තියෙනවා කියලා උපකල්පනය කරනවා)
    // ReportRepository.java

    // 🔥 2. TOP SUPPLIERS
    @Query(value = """
    SELECT 
        s.id, 
        s.name, 
        s.phone, 
        COUNT(p.id) as purchase_count, 
        COALESCE(SUM(p.grand_total), 0) as total_purchased
    FROM purchase p
    JOIN suppliers s ON s.id = p.supplier_id
    -- WHERE කොටස අයින් කළ නිසා branchId මෙතනට ඕන නෑ
    GROUP BY s.id, s.name, s.phone
    ORDER BY total_purchased DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> topSuppliersRaw(@Param("limitValue") int limitValue);

    @Query(value = """
    SELECT COALESCE(SUM(amount), 0)
    FROM expenses
    WHERE (:branchId IS NULL OR branch_id = :branchId)
      AND date_time BETWEEN :fromDate AND :toDate
""", nativeQuery = true)
    double getTotalExpenses(@Param("branchId") Long branchId,
                            @Param("fromDate") LocalDateTime fromDate,
                            @Param("toDate") LocalDateTime toDate);
}