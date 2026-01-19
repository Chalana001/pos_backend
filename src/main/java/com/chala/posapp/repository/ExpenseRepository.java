package com.chala.posapp.repository;

import com.chala.posapp.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByShiftId(Long shiftId);
}
