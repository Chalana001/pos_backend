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

    List<Item> findAllByNameIgnoreCase(String name);

    List<Item> findAllByBarcodeIn(List<String> barcodes);

    List<Item> findByNameContainingIgnoreCase(String name);

    List<Item> findByNameContainingIgnoreCaseOrBarcodeContainingIgnoreCase(String name, String barcode);

    @Query("SELECT i FROM Item i WHERE LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR (i.altName IS NOT NULL AND LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "OR LOWER(i.barcode) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<Item> searchForPurchase(@Param("search") String search);

    // PERF-08 FIX: FULLTEXT search for POS product lookup (search length >= 3).
    // Requires V11 migration: ALTER TABLE items ADD FULLTEXT INDEX ft_items_name_barcode (name, barcode, alt_name).
    // Falls back to LIKE in ItemService for short (<3 char) searches such as single-digit barcodes.
    @Query(value = """
        SELECT * FROM items i
        WHERE MATCH(i.name, i.barcode) AGAINST (:search IN BOOLEAN MODE)
          AND i.active = true
        ORDER BY MATCH(i.name, i.barcode) AGAINST (:search IN BOOLEAN MODE) DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Item> searchForPosFt(@Param("search") String search);

    // PERF-08 FIX: FULLTEXT search for purchase item lookup (search length >= 3).
    @Query(value = """
        SELECT * FROM items i
        WHERE MATCH(i.name, i.barcode) AGAINST (:search IN BOOLEAN MODE)
        ORDER BY MATCH(i.name, i.barcode) AGAINST (:search IN BOOLEAN MODE) DESC
        LIMIT 50
        """, nativeQuery = true)
    List<Item> searchForPurchaseFt(@Param("search") String search);

    List<Item> findByStockProcessingEnabledTrueAndActiveTrueOrderByNameAsc();

    // PERF-01 FIX: itemsWithBranchStockRaw() and itemsWithTotalStockRaw() removed —
    // both returned List<Object[]> with no LIMIT/OFFSET (10,000 items = 10,000 rows
    // loaded on every stock page). Zero callers confirmed: stock listing is served
    // by StockBatchRepository.getStockSummary() which already has proper pagination.

    // BUG-09 FIX: Removed redundant searchItems() — it had no callers and duplicated
    // the simpler version of searchItemsWithFilters() below (which is the canonical method).

    @Query("""
            SELECT i FROM Item i
            LEFT JOIN i.subCategory sc
            LEFT JOIN sc.category c
            WHERE (:search IS NULL OR :search = ''
                OR LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%'))
                OR (i.altName IS NOT NULL AND LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%')))
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
