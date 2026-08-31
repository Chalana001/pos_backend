package com.chala.posapp.repository;

import com.chala.posapp.entity.ExpenseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpenseTypeRepository extends JpaRepository<ExpenseType, Long> {
    List<ExpenseType> findAllByOrderByNameAsc();
    List<ExpenseType> findByActiveTrueOrderByNameAsc();
    Optional<ExpenseType> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
}
