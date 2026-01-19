package com.chala.posapp.repository;

import com.chala.posapp.dto.LowStockView;
import com.chala.posapp.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.chala.posapp.dto.report.LowStockResponse;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findByBranchIdAndItemId(Long branchId, Long itemId);

    List<Stock> findByBranchId(Long branchId);

    // ✅ Optimized Low Stock Query (NO N+1)
    @Query("""
        SELECT 
            i.id as itemId,
            i.barcode as barcode,
            i.name as itemName,
            s.quantity as stockQty,
            i.reorderLevel as reorderLevel
        FROM Stock s
        JOIN Item i ON i.id = s.itemId
        WHERE s.branchId = :branchId
          AND i.active = true
          AND s.quantity <= i.reorderLevel
        ORDER BY s.quantity ASC
    """)

    List<LowStockView> findLowStock(@Param("branchId") Long branchId);

    @Query(value = """
    SELECT 
        i.id AS item_id,
        i.name AS item_name,
        s.quantity,
        i.reorder_level
    FROM stock s
    JOIN items i ON i.id = s.item_id
    WHERE s.branch_id = :branchId
      AND s.quantity <= i.reorder_level
    ORDER BY s.quantity ASC
""", nativeQuery = true)
    List<Object[]> lowStockRaw(@Param("branchId") Long branchId);

}
