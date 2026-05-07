package com.chala.posapp.repository;

import com.chala.posapp.entity.Order;
import com.chala.posapp.entity.OrderStatus;
import com.chala.posapp.entity.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByInvoiceNo(String invoiceNo);

    Optional<Order> findByClientSaleId(String clientSaleId);

    long countByBranchId(Long branchId);

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

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    Page<Order> findByCustomerIdAndOrderType(Long customerId, OrderType orderType, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.customerId = :customerId " +
            "AND o.orderType = 'CREDIT' AND o.dueAmount > 0 " +
            "ORDER BY o.createdAt ASC")
    List<Order> findPendingCreditOrders(@Param("customerId") Long customerId);

    Page<Order> findByInvoiceNoContainingIgnoreCase(String invoiceNo, Pageable pageable);

    Page<Order> findByInvoiceNoContainingIgnoreCaseAndBranchId(String search, Long branchId, Pageable pageable);

    Page<Order> findByBranchId(Long branchId, Pageable pageable);

    @Query("""
        SELECT o
        FROM Order o
        WHERE o.branchId = :branchId
          AND o.cashierUserId = :cashierId
          AND o.createdAt >= :from
          AND (:to IS NULL OR o.createdAt <= :to)
        """)
    Page<Order> findShiftOrders(
            @Param("branchId") Long branchId,
            @Param("cashierId") Long cashierId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );

    @Query("""
        SELECT o
        FROM Order o
        LEFT JOIN Customer c ON c.id = o.customerId
        LEFT JOIN User u ON u.id = o.cashierUserId
        WHERE (:branchId IS NULL OR o.branchId = :branchId)
          AND (:search IS NULL OR :search = ''
               OR LOWER(o.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:from IS NULL OR o.createdAt >= :from)
          AND (:to IS NULL OR o.createdAt <= :to)
          AND (:orderType IS NULL OR o.orderType = :orderType)
          AND (:customerType IS NULL OR :customerType = 'ALL'
               OR (:customerType = 'WALK_IN' AND o.customerId IS NULL)
               OR (:customerType = 'CUSTOMER' AND o.customerId IS NOT NULL))
          AND (:cashierId IS NULL OR o.cashierUserId = :cashierId)
          AND (:totalAmount IS NULL OR :totalOperator IS NULL OR :totalOperator = 'ALL'
               OR (:totalOperator = 'EQUAL' AND o.grandTotal = :totalAmount)
               OR (:totalOperator = 'GREATER_THAN' AND o.grandTotal > :totalAmount)
               OR (:totalOperator = 'LESS_THAN' AND o.grandTotal < :totalAmount))
        """)
    Page<Order> searchOrders(
            @Param("search") String search,
            @Param("branchId") Long branchId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("orderType") OrderType orderType,
            @Param("customerType") String customerType,
            @Param("cashierId") Long cashierId,
            @Param("totalOperator") String totalOperator,
            @Param("totalAmount") Double totalAmount,
            Pageable pageable
    );
}
