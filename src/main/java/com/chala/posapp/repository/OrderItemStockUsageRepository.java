package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderItemStockUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemStockUsageRepository extends JpaRepository<OrderItemStockUsage, Long> {
    List<OrderItemStockUsage> findByOrderItemId(Long orderItemId);
}
