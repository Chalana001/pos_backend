package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportRepository extends JpaRepository<Order, Long> {

    // ✅ total sales (grand_total)
    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalSales(@Param("branchId") Long branchId,
                      @Param("fromDate") LocalDateTime fromDate,
                      @Param("toDate") LocalDateTime toDate);

    // ✅ cash sales
    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CASH'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    // ✅ credit sales
    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CREDIT'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("branchId") Long branchId,
                       @Param("fromDate") LocalDateTime fromDate,
                       @Param("toDate") LocalDateTime toDate);

    // ✅ total discount (bill_discount)
    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0)
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double totalDiscount(@Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    // ✅ order count
    @Query(value = """
        SELECT COUNT(*)
        FROM orders o
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long totalOrders(@Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    // ✅ TOP SELLING ITEMS
    @Query(value = """
        SELECT 
            oi.item_id,
            oi.item_name,
            COALESCE(SUM(oi.qty),0) AS qty_sold,
            COALESCE(SUM(oi.line_total),0) AS revenue
        FROM order_items oi
        JOIN orders o ON o.id = oi.order_id
        WHERE (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
        GROUP BY oi.item_id, oi.item_name
        ORDER BY qty_sold DESC
        LIMIT :limitValue
    """, nativeQuery = true)
    List<Object[]> topSellingRaw(@Param("branchId") Long branchId,
                                 @Param("fromDate") LocalDateTime fromDate,
                                 @Param("toDate") LocalDateTime toDate,
                                 @Param("limitValue") int limitValue);

    @Query(value = """
    SELECT
        oi.item_id,
        oi.item_name,
        COALESCE(SUM(oi.qty),0) AS qty_sold,
        COALESCE(SUM(oi.line_total),0) AS revenue,
        COALESCE(SUM(oi.qty * i.cost_price),0) AS cost,
        COALESCE(SUM(oi.line_total - (oi.qty * i.cost_price)),0) AS profit
    FROM order_items oi
    JOIN orders o ON o.id = oi.order_id
    JOIN items i ON i.id = oi.item_id
    WHERE (:branchId = 0 OR o.branch_id = :branchId)
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    GROUP BY oi.item_id, oi.item_name
    ORDER BY profit DESC
    LIMIT :limitValue
""", nativeQuery = true)
    List<Object[]> profitReportRaw(@Param("branchId") Long branchId,
                                   @Param("fromDate") LocalDateTime fromDate,
                                   @Param("toDate") LocalDateTime toDate,
                                   @Param("limitValue") int limitValue);

}
