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

    List<Item> findAllByBarcodeIn(List<String> barcodes);

    List<Item> findByNameContainingIgnoreCase(String name);

    List<Item> findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String name, String barcode);

    @Query(value = """
            SELECT 
                i.id,                       -- [0]
                i.barcode,                  -- [1]
                i.name,                     -- [2]
                c.name AS cat_name,         -- [3] Category Name
                sc.name AS sub_cat_name,    -- [4] Sub Category Name
                i.cost_price,               -- [5]
                i.selling_price,            -- [6]
                i.reorder_level,            -- [7]
                i.active,                   -- [8]
                i.created_at,               -- [9]
                COALESCE(SUM(sb.quantity), 0) AS qty -- [10]
            FROM items i
            LEFT JOIN sub_categories sc ON i.sub_category_id = sc.id
            LEFT JOIN categories c ON sc.category_id = c.id
            LEFT JOIN stock_batches sb 
                ON sb.item_id = i.id AND sb.branch_id = :branchId
            GROUP BY i.id, i.barcode, i.name, c.name, sc.name, i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at
            ORDER BY i.id DESC
            """, nativeQuery = true)
    List<Object[]> itemsWithBranchStockRaw(@Param("branchId") Long branchId);

    @Query(value = """
            SELECT 
                i.id,                       -- [0]
                i.barcode,                  -- [1]
                i.name,                     -- [2]
                c.name AS cat_name,         -- [3] Category Name
                sc.name AS sub_cat_name,    -- [4] Sub Category Name
                i.cost_price,               -- [5]
                i.selling_price,            -- [6]
                i.reorder_level,            -- [7]
                i.active,                   -- [8]
                i.created_at,               -- [9]
                COALESCE(SUM(sb.quantity), 0) AS qty -- [10]
            FROM items i
            LEFT JOIN sub_categories sc ON i.sub_category_id = sc.id
            LEFT JOIN categories c ON sc.category_id = c.id
            LEFT JOIN stock_batches sb ON sb.item_id = i.id
            GROUP BY i.id, i.barcode, i.name, c.name, sc.name, i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at
            ORDER BY i.id DESC
            """, nativeQuery = true)
    List<Object[]> itemsWithTotalStockRaw();
}