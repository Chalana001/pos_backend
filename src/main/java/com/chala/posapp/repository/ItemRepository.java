package com.chala.posapp.repository;

import com.chala.posapp.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {
    boolean existsByBarcode(String barcode);
    Optional<Item> findByBarcode(String barcode);

    List<Item> findByNameContainingIgnoreCase(String name);

    //Branch qty list
    @Query(value = """
            SELECT\s
                                i.id, i.barcode, i.name, i.category,
                                i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at,
                                s.quantity AS qty
                            FROM items i
                            INNER JOIN stock s\s
                                ON s.item_id = i.id AND s.branch_id = :branchId
                            ORDER BY i.id DESC
            
""", nativeQuery = true)
    List<Object[]> itemsWithBranchStockRaw(@Param("branchId") Long branchId);

    //Total qty (All branches)
    @Query(value = """
    SELECT 
        i.id, i.barcode, i.name, i.category,
        i.cost_price, i.selling_price, i.reorder_level, i.active,
        COALESCE(SUM(s.quantity),0) AS qty
    FROM items i
    LEFT JOIN stock s ON s.item_id = i.id
    GROUP BY i.id, i.barcode, i.name, i.category, i.cost_price, i.selling_price, i.reorder_level, i.active
    ORDER BY i.id DESC
""", nativeQuery = true)
    List<Object[]> itemsWithTotalStockRaw();


}
