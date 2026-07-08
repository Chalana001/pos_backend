package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DashboardRepository extends JpaRepository<Order, Long> {

    /**
     * PERF FIX: Single consolidated query that fetches all dashboard KPIs in ONE round-trip.
     * Previously: 9 separate queries were fired from DashboardService.todayKpis().
     * Now:  one sub-select per metric, but only ONE network round-trip to the DB.
     *
     * Returns Object[] with indices:
     *  [0] todaySales, [1] cashSales, [2] creditSales, [3] todayDiscount,
     *  [4] todayOrders, [5] todayExpenses, [6] todayCashDrops,
     *  [7] lowStockCount, [8] totalDue
     */
    @Query(value = """
        SELECT
          COALESCE((SELECT SUM(o.grand_total) FROM orders o
                    WHERE (:branchId = 0 OR o.branch_id = :branchId)
                      AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0)
            AS todaySales,

          COALESCE((SELECT SUM(
              COALESCE(o.sale_paid_amount,
                CASE WHEN o.order_type = 'CASH' THEN
                  CASE WHEN o.paid_amount < o.grand_total THEN o.paid_amount ELSE o.grand_total END
                ELSE 0 END))
            FROM orders o
            WHERE (:branchId = 0 OR o.branch_id = :branchId)
              AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0)
            AS cashSales,

          COALESCE((SELECT SUM(
              COALESCE(o.sale_due_amount,
                CASE WHEN o.order_type = 'CREDIT' THEN o.grand_total ELSE 0 END))
            FROM orders o
            WHERE (:branchId = 0 OR o.branch_id = :branchId)
              AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0)
            AS creditSales,

          COALESCE((SELECT SUM(o.bill_discount) FROM orders o
                    WHERE (:branchId = 0 OR o.branch_id = :branchId)
                      AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0)
            AS todayDiscount,

          COALESCE((SELECT COUNT(*) FROM orders o
                    WHERE (:branchId = 0 OR o.branch_id = :branchId)
                      AND o.status = 'COMPLETED' AND o.created_at BETWEEN :fromDate AND :toDate), 0)
            AS todayOrders,

          COALESCE((SELECT SUM(e.amount) FROM expenses e
                    WHERE (:branchId = 0 OR e.branch_id = :branchId)
                      AND COALESCE(e.count_in_profit_report, TRUE) = TRUE
                      AND e.created_at BETWEEN :fromDate AND :toDate), 0)
            AS todayExpenses,

          COALESCE((SELECT SUM(cd.amount) FROM cash_drops cd
                    WHERE (:branchId = 0 OR cd.branch_id = :branchId)
                      AND cd.created_at BETWEEN :fromDate AND :toDate), 0)
            AS todayCashDrops,

          COALESCE((SELECT COUNT(*) FROM stock s
                    JOIN items i ON i.id = s.item_id
                    WHERE (:branchId = 0 OR s.branch_id = :branchId)
                      AND s.quantity <= i.reorder_level), 0)
            AS lowStockCount,

          COALESCE((SELECT SUM(c.due_amount) FROM customers c WHERE c.due_amount > 0), 0)
            AS totalDue
    """, nativeQuery = true)
    List<Object[]> todayKpisAllInOne(@Param("branchId") Long branchId,
                                     @Param("fromDate") LocalDateTime fromDate,
                                     @Param("toDate") LocalDateTime toDate);

    // DUP-01 FIX: Removed 9 individual KPI query methods (todaySales, cashSales, creditSales,
    // todayDiscount, todayOrders, todayExpenses, todayCashDrops, lowStockCount, totalDue).
    // All had ZERO callers — todayKpisAllInOne() above replaced all 9 in one DB round-trip.

    // DUP-03/04 FIX: dailySalesRaw() and monthlySalesRaw() removed from here.
    // Canonical versions live in ReportRepository. DashboardService now injects ReportRepository
    // directly to call them — one source of truth for both chart types.

}
