package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.StockAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    List<StockAdjustment> findByBranchIdOrderByCreatedAtDesc(Long branchId);

    List<StockAdjustment> findByBranchIdAndItemIdOrderByCreatedAtDesc(Long branchId, Long itemId);
}
