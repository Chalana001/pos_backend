package com.chala.posapp.repository;

import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.ShiftStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CashShiftRepository extends JpaRepository<CashShift, Long>, JpaSpecificationExecutor<CashShift> {

    Optional<CashShift> findByBranchIdAndCashierUserIdAndStatus(@Param("branchId") Long branchId, @Param("cashierUserId") Long cashierUserId, @Param("status") ShiftStatus status);

    List<CashShift> findAllByBranchIdAndStatus(Long branchId, ShiftStatus status);

    Optional<CashShift> findFirstByBranchIdAndStatus(Long branchId, ShiftStatus status);

    List<CashShift> findAllByStatus(ShiftStatus shiftStatus);

    Optional<CashShift> findByCashierUserIdAndStatus(Long cashierUserId, ShiftStatus status);

    // RPT-03: Shift summary / Z-Report — fetch closed shifts in a date range
    @Query("""
        SELECT cs FROM CashShift cs
        WHERE (:branchId IS NULL OR cs.branchId = :branchId)
          AND (:cashierUserId IS NULL OR cs.cashierUserId = :cashierUserId)
          AND cs.openedAt >= :fromDate
          AND cs.openedAt <= :toDate
        ORDER BY cs.openedAt DESC
        """)
    Page<CashShift> findShiftsForReport(
            @Param("branchId") Long branchId,
            @Param("cashierUserId") Long cashierUserId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);
}
