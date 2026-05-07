package com.chala.posapp.repository;

import com.chala.posapp.entity.Expense;
import com.chala.posapp.entity.ExpenseCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByShiftId(Long shiftId);
    Page<Expense> findByShiftId(Long shiftId, Pageable pageable);
    List<Expense> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);
    List<Expense> findByBranchIdAndCreatedAtBetweenOrderByCreatedAtDesc(Long branchId, LocalDateTime from, LocalDateTime to);

    @Query("SELECT e FROM Expense e WHERE " +
            "(:branchId IS NULL OR :branchId = 0 OR e.branchId = :branchId) AND " +
            "(:cashierUserId IS NULL OR e.cashierUserId = :cashierUserId) AND " +
            "(:category IS NULL OR e.category = :category) AND " +
            "(:shiftId IS NULL OR e.shiftId = :shiftId) AND " +
            "(:search IS NULL OR LOWER(e.description) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
            "(:from IS NULL OR e.createdAt >= :from) AND " +
            "(:to IS NULL OR e.createdAt <= :to)")
    Page<Expense> findWithFilters(
            @Param("branchId") Long branchId,
            @Param("cashierUserId") Long cashierUserId,
            @Param("category") ExpenseCategory category,
            @Param("shiftId") Long shiftId,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
