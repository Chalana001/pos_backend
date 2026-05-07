package com.chala.posapp.repository;

import com.chala.posapp.entity.Item;
import com.chala.posapp.entity.ItemType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    @Query("SELECT i FROM Item i LEFT JOIN i.subCategory sc LEFT JOIN sc.category c " +
            "WHERE LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(i.barcode) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Item> searchItems(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT i FROM Item i
            LEFT JOIN i.subCategory sc
            LEFT JOIN sc.category c
            WHERE (:search IS NULL OR :search = ''
                OR LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(i.barcode) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR CONCAT('', i.id) LIKE CONCAT('%', :search, '%'))
            AND (:categoryId IS NULL OR c.id = :categoryId)
            AND (:subCategoryId IS NULL OR sc.id = :subCategoryId)
            AND (:itemType IS NULL OR i.itemType = :itemType)
            AND (:active IS NULL OR i.active = :active)
            AND (:kotEnabled IS NULL OR i.kotEnabled = :kotEnabled)
            AND (:priceAmount IS NULL OR :priceOperator IS NULL OR :priceOperator = 'ALL'
                OR (:priceField = 'COST' AND :priceOperator = 'EQUAL' AND i.costPrice = :priceAmount)
                OR (:priceField = 'COST' AND :priceOperator = 'GREATER_THAN' AND i.costPrice > :priceAmount)
                OR (:priceField = 'COST' AND :priceOperator = 'LESS_THAN' AND i.costPrice < :priceAmount)
                OR (:priceField = 'SELLING' AND :priceOperator = 'EQUAL' AND i.sellingPrice = :priceAmount)
                OR (:priceField = 'SELLING' AND :priceOperator = 'GREATER_THAN' AND i.sellingPrice > :priceAmount)
                OR (:priceField = 'SELLING' AND :priceOperator = 'LESS_THAN' AND i.sellingPrice < :priceAmount))
            """)
    Page<Item> searchItemsWithFilters(
            @Param("search") String search,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId,
            @Param("itemType") ItemType itemType,
            @Param("active") Boolean active,
            @Param("kotEnabled") Boolean kotEnabled,
            @Param("priceField") String priceField,
            @Param("priceOperator") String priceOperator,
            @Param("priceAmount") BigDecimal priceAmount,
            Pageable pageable);
}
