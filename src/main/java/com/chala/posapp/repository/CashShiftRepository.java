package com.chala.posapp.repository;

import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CashShiftRepository extends JpaRepository<CashShift, Long>, JpaSpecificationExecutor<CashShift> {

    Optional<CashShift> findByBranchIdAndCashierUserIdAndStatus(Long branchId, Long cashierUserId, ShiftStatus status);

    Optional<CashShift> findTopByBranchIdAndCashierUserIdOrderByOpenedAtDesc(
            @Param("branchId") Long branchId,
            @Param("cashierUserId") Long cashierUserId
    );
    List<CashShift> findAllByBranchIdAndStatus(Long branchId, ShiftStatus status);

    Optional<CashShift> findFirstByBranchIdAndStatus(Long branchId, ShiftStatus status);

}
