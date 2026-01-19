package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;


public interface DashboardRepository extends JpaRepository<Order, Long> {

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE o.branch_id = :branchId
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todaySales(@Param("branchId") Long branchId,
                      @Param("fromDate") LocalDateTime fromDate,
                      @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE o.branch_id = :branchId
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CASH'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE o.branch_id = :branchId
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CREDIT'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("branchId") Long branchId,
                       @Param("fromDate") LocalDateTime fromDate,
                       @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0)
        FROM orders o
        WHERE o.branch_id = :branchId
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayDiscount(@Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COUNT(*)
        FROM orders o
        WHERE o.branch_id = :branchId
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long todayOrders(@Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    // ✅ Expenses today
    @Query(value = """
        SELECT COALESCE(SUM(e.amount),0)
        FROM expenses e
        WHERE e.branch_id = :branchId
          AND e.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayExpenses(@Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    // ✅ Cash drops today
    @Query(value = """
        SELECT COALESCE(SUM(cd.amount),0)
        FROM cash_drops cd
        WHERE cd.branch_id = :branchId
          AND cd.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayCashDrops(@Param("branchId") Long branchId,
                          @Param("fromDate") LocalDateTime fromDate,
                          @Param("toDate") LocalDateTime toDate);

    // ✅ Low stock count
    @Query(value = """
        SELECT COUNT(*)
        FROM stock s
        JOIN items i ON i.id = s.item_id
        WHERE s.branch_id = :branchId
          AND s.quantity <= i.reorder_level
    """, nativeQuery = true)
    long lowStockCount(@Param("branchId") Long branchId);

    // ✅ Total due (all customers)
    @Query(value = """
        SELECT COALESCE(SUM(c.due_amount),0)
        FROM customers c
        WHERE c.due_amount > 0
    """, nativeQuery = true)
    double totalDue();

    // ✅ Daily Sales
    @Query(value = """
    SELECT DATE(o.created_at) AS day, COALESCE(SUM(o.grand_total),0) AS sales
    FROM orders o
    WHERE o.branch_id = :branchId
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    GROUP BY DATE(o.created_at)
    ORDER BY day
""", nativeQuery = true)
    List<Object[]> dailySalesRaw(@Param("branchId") Long branchId,
                                 @Param("fromDate") LocalDateTime fromDate,
                                 @Param("toDate") LocalDateTime toDate);


    // ✅ Monthly Sales
    @Query(value = """
    SELECT DATE_FORMAT(o.created_at, '%Y-%m') AS month, COALESCE(SUM(o.grand_total),0) AS sales
    FROM orders o
    WHERE o.branch_id = :branchId
      AND o.status = 'COMPLETED'
      AND o.created_at BETWEEN :fromDate AND :toDate
    GROUP BY DATE_FORMAT(o.created_at, '%Y-%m')
    ORDER BY month
""", nativeQuery = true)
    List<Object[]> monthlySalesRaw(@Param("branchId") Long branchId,
                                   @Param("fromDate") LocalDateTime fromDate,
                                   @Param("toDate") LocalDateTime toDate);

}
