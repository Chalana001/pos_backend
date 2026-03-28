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
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todaySales(@Param("tenantId") String tenantId,
                      @Param("branchId") Long branchId,
                      @Param("fromDate") LocalDateTime fromDate,
                      @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CASH'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double cashSales(@Param("tenantId") String tenantId,
                     @Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.grand_total),0)
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.order_type = 'CREDIT'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double creditSales(@Param("tenantId") String tenantId,
                       @Param("branchId") Long branchId,
                       @Param("fromDate") LocalDateTime fromDate,
                       @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COALESCE(SUM(o.bill_discount),0)
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayDiscount(@Param("tenantId") String tenantId,
                         @Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    @Query(value = """
        SELECT COUNT(*)
        FROM orders o
        WHERE o.tenant_id = :tenantId
          AND (:branchId = 0 OR o.branch_id = :branchId)
          AND o.status = 'COMPLETED'
          AND o.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    long todayOrders(@Param("tenantId") String tenantId,
                     @Param("branchId") Long branchId,
                     @Param("fromDate") LocalDateTime fromDate,
                     @Param("toDate") LocalDateTime toDate);

    // ✅ Expenses today
    @Query(value = """
        SELECT COALESCE(SUM(e.amount),0)
        FROM expenses e
        WHERE e.tenant_id = :tenantId
          AND (:branchId = 0 OR e.branch_id = :branchId)
          AND e.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayExpenses(@Param("tenantId") String tenantId,
                         @Param("branchId") Long branchId,
                         @Param("fromDate") LocalDateTime fromDate,
                         @Param("toDate") LocalDateTime toDate);

    // ✅ Cash drops today
    @Query(value = """
        SELECT COALESCE(SUM(cd.amount),0)
        FROM cash_drops cd
        WHERE cd.tenant_id = :tenantId
          AND (:branchId = 0 OR cd.branch_id = :branchId)
          AND cd.created_at BETWEEN :fromDate AND :toDate
    """, nativeQuery = true)
    double todayCashDrops(@Param("tenantId") String tenantId,
                          @Param("branchId") Long branchId,
                          @Param("fromDate") LocalDateTime fromDate,
                          @Param("toDate") LocalDateTime toDate);

    // ✅ Low stock count
    @Query(value = """
        SELECT COUNT(*)
        FROM stock s
        JOIN items i ON i.id = s.item_id
        WHERE s.tenant_id = :tenantId
          AND (:branchId = 0 OR s.branch_id = :branchId)
          AND s.quantity <= i.reorder_level
    """, nativeQuery = true)
    long lowStockCount(@Param("tenantId") String tenantId,
                       @Param("branchId") Long branchId);

    // ✅ Total due (all customers for this tenant)
    @Query(value = """
        SELECT COALESCE(SUM(c.due_amount),0)
        FROM customers c
        WHERE c.tenant_id = :tenantId
          AND c.due_amount > 0
    """, nativeQuery = true)
    double totalDue(@Param("tenantId") String tenantId);

    // ✅ Daily Sales
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
    List<Object[]> dailySalesRaw(@Param("tenantId") String tenantId,
                                 @Param("branchId") Long branchId,
                                 @Param("fromDate") LocalDateTime fromDate,
                                 @Param("toDate") LocalDateTime toDate);


    // ✅ Monthly Sales
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
    List<Object[]> monthlySalesRaw(@Param("tenantId") String tenantId,
                                   @Param("branchId") Long branchId,
                                   @Param("fromDate") LocalDateTime fromDate,
                                   @Param("toDate") LocalDateTime toDate);

}
