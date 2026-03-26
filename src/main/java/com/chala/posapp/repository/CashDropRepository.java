package com.chala.posapp.repository;

import com.chala.posapp.entity.CashDrop;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CashDropRepository extends JpaRepository<CashDrop, Long> {
    List<CashDrop> findByShiftId(Long shiftId);

    @Query("SELECT c FROM CashDrop c WHERE " +
            "(:branchId IS NULL OR :branchId = 0 OR c.branchId = :branchId) AND " +
            "(:shiftId IS NULL OR c.shiftId = :shiftId) AND " +
            "(:from IS NULL OR c.createdAt >= :from) AND " +
            "(:to IS NULL OR c.createdAt <= :to)")
    Page<CashDrop> findWithFilters(
            @Param("branchId") Long branchId,
            @Param("shiftId") Long shiftId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
