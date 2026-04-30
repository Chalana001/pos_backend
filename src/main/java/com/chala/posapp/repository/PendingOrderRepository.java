package com.chala.posapp.repository;

import com.chala.posapp.entity.PendingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PendingOrderRepository extends JpaRepository<PendingOrder, Long> {
    Optional<PendingOrder> findByTableId(Long tableId);
    List<PendingOrder> findByBranchIdOrderByUpdatedAtDesc(Long branchId);
    void deleteByTableId(Long tableId);
}
