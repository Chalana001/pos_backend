package com.chala.posapp.repository;

import com.chala.posapp.entity.DiningTable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiningTableRepository extends JpaRepository<DiningTable, Long> {
    List<DiningTable> findByBranchIdOrderByTableNameAsc(Long branchId);
    boolean existsByBranchIdAndTableNameIgnoreCase(Long branchId, String tableName);
    boolean existsByBranchIdAndTableNameIgnoreCaseAndIdNot(Long branchId, String tableName, Long id);
}
