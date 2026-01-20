package com.chala.posapp.repository;

import com.chala.posapp.entity.CashShift;
import com.chala.posapp.entity.ShiftStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CashShiftRepository extends JpaRepository<CashShift, Long> {

    Optional<CashShift> findByBranchIdAndCashierUserIdAndStatus(Long branchId, Long cashierUserId, ShiftStatus status);

    Optional<CashShift> findTopByBranchIdAndCashierUserIdOrderByOpenedAtDesc(Long branchId, Long cashierUserId);

    Optional<CashShift> findFirstByBranchIdAndStatus(Long branchId, ShiftStatus status);

}
