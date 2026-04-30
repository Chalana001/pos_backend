package com.chala.posapp.repository;

import com.chala.posapp.entity.PendingOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingOrderItemRepository extends JpaRepository<PendingOrderItem, Long> {
    List<PendingOrderItem> findByPendingOrderId(Long pendingOrderId);
    void deleteByPendingOrderId(Long pendingOrderId);
}
