package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByInvoiceNo(String invoiceNo);

    long countByBranchId(Long branchId);

    // ✅ SUM of CASH sales during shift
    @Query("""
        SELECT COALESCE(SUM(o.grandTotal), 0)
        FROM Order o
        WHERE o.branchId = :branchId
          AND o.cashierUserId = :cashierId
          AND o.orderType = :orderType
          AND o.status = :status
          AND o.createdAt BETWEEN :from AND :to
    """)
    double sumCashSales(
            @Param("branchId") Long branchId,
            @Param("cashierId") Long cashierId,
            @Param("orderType") OrderType orderType,
            @Param("status") OrderStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
