package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderItemStockUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemStockUsageRepository extends JpaRepository<OrderItemStockUsage, Long> {
    List<OrderItemStockUsage> findByOrderItemId(Long orderItemId);

    @Query("SELECT COALESCE(SUM(u.restoredQuantity), 0) FROM OrderItemStockUsage u WHERE u.orderItemId = :orderItemId")
    int sumRestoredQtyByOrderItemId(@Param("orderItemId") Long orderItemId);
}
