package com.chala.posapp.repository;

import com.chala.posapp.entity.OrderReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderReturnItemRepository extends JpaRepository<OrderReturnItem, Long> {

    // All return-item rows for a given OrderReturn record
    List<OrderReturnItem> findByOrderReturnId(Long orderReturnId);

    // All return-item rows that reference a specific original OrderItem
    // Used to calculate how many units have already been returned for that line
    List<OrderReturnItem> findByOrderItemId(Long orderItemId);

    // Sum of already-returned qty for one original order-item line
    // Prevents returning more than was originally sold
    @Query("""
        SELECT COALESCE(SUM(ri.returnQty), 0)
        FROM OrderReturnItem ri
        WHERE ri.orderItemId = :orderItemId
    """)
    int sumReturnedQtyByOrderItemId(@Param("orderItemId") Long orderItemId);
}
