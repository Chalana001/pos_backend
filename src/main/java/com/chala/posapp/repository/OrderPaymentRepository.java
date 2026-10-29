package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderPaymentRepository extends JpaRepository<OrderPayment, Long> {

    Optional<OrderPayment> findByOrderId(Long orderId);

    /**
     * Per-method tender totals for one shift's window. The ledger row is the
     * truth: amount + shortfall = the order's grand total, so one grouped query
     * gives the Close Shift dialog its counts and totals without double counting
     * and without re-deriving anything from orders.
     *
     * paid_at (not order created_at) defines the window because that is when the
     * money entered the open shift — the same moment the shift buckets are
     * incremented, offline imports included.
     */
    @Query("""
            SELECT op.paymentMethod AS code,
                   COUNT(op.id) AS transactionCount,
                   SUM(op.amount + COALESCE(op.shortfallAmount, 0)) AS amount
            FROM OrderPayment op
            JOIN Order o ON o.id = op.orderId
            WHERE o.branchId = :branchId
              AND o.cashierUserId = :cashierUserId
              AND o.status <> com.chala.posapp.entity.OrderStatus.CANCELED
              AND op.paidAt >= :from
              AND (:to IS NULL OR op.paidAt <= :to)
            GROUP BY op.paymentMethod
            ORDER BY SUM(op.amount + COALESCE(op.shortfallAmount, 0)) DESC
            """)
    List<ShiftMethodTotalProjection> sumShiftTotalsByMethod(
            @Param("branchId") Long branchId,
            @Param("cashierUserId") Long cashierUserId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    interface ShiftMethodTotalProjection {
        String getCode();

        long getTransactionCount();

        double getAmount();
    }
}
