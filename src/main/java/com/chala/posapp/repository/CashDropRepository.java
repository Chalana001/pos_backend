package com.chala.posapp.repository;

import com.chala.posapp.entity.CashDrop;
import com.chala.posapp.dto.CashDropSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CashDropRepository extends JpaRepository<CashDrop, Long> {
    List<CashDrop> findByShiftId(Long shiftId);
    long countByBankAccountId(Long bankAccountId);

    @Query("SELECT c FROM CashDrop c WHERE " +
            "(:branchId IS NULL OR :branchId = 0 OR c.branchId = :branchId) AND " +
            "(:shiftId IS NULL OR c.shiftId = :shiftId) AND " +
            "(:cashierUserId IS NULL OR c.cashierUserId = :cashierUserId) AND " +
            "(:bankAccountId IS NULL OR c.bankAccountId = :bankAccountId) AND " +
            "(:search IS NULL OR LOWER(c.reason) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
            "(:from IS NULL OR c.createdAt >= :from) AND " +
            "(:to IS NULL OR c.createdAt <= :to)")
    Page<CashDrop> findWithFilters(
            @Param("branchId") Long branchId,
            @Param("shiftId") Long shiftId,
            @Param("cashierUserId") Long cashierUserId,
            @Param("bankAccountId") Long bankAccountId,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query("""
            SELECT new com.chala.posapp.dto.CashDropSummaryResponse(
                COALESCE(SUM(c.amount), 0),
                COUNT(c),
                COALESCE(AVG(c.amount), 0)
            )
            FROM CashDrop c
            WHERE (:branchId IS NULL OR :branchId = 0 OR c.branchId = :branchId)
              AND (:shiftId IS NULL OR c.shiftId = :shiftId)
              AND (:cashierUserId IS NULL OR c.cashierUserId = :cashierUserId)
              AND (:bankAccountId IS NULL OR c.bankAccountId = :bankAccountId)
              AND (:search IS NULL OR LOWER(c.reason) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:from IS NULL OR c.createdAt >= :from)
              AND (:to IS NULL OR c.createdAt <= :to)
            """)
    CashDropSummaryResponse summarizeWithFilters(
            @Param("branchId") Long branchId,
            @Param("shiftId") Long shiftId,
            @Param("cashierUserId") Long cashierUserId,
            @Param("bankAccountId") Long bankAccountId,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
