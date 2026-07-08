package com.chala.posapp.repository;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.entity.stock.StockBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    @Query("SELECT COALESCE(SUM(sb.quantity), 0) FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId")
    Integer getTotalQuantityByItemAndBranch(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    List<StockBatch> findByBranchIdAndItemIdAndQuantityGreaterThanOrderByIdAsc(Long branchId, Long itemId, Double quantity);

    List<StockBatch> findByBranchIdAndItemIdAndQuantityLessThan(Long branchId, Long itemId, Integer quantity);

    /**
     * FIFO batch lookup for sale stock consumption.
     * Uses PESSIMISTIC_WRITE lock to prevent race conditions when multiple
     * concurrent transactions deduct from the same batch simultaneously.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.branch.id = :branchId AND sb.item.id = :itemId AND sb.quantity > 0
            ORDER BY CASE WHEN sb.sourceType = com.chala.posapp.entity.stock.StockBatchSourceType.PROCESSING THEN 0 ELSE 1 END,
                     sb.receivedAt ASC,
                     sb.id ASC
            """)
    List<StockBatch> findAvailableBatches(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    /**
     * FIFO batch lookup by selling price — also locked to prevent concurrent deduction races.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.branch.id = :branchId
              AND sb.item.id = :itemId
              AND sb.quantity > 0
              AND sb.sellingPrice = :sellingPrice
            ORDER BY CASE WHEN sb.sourceType = com.chala.posapp.entity.stock.StockBatchSourceType.PROCESSING THEN 0 ELSE 1 END,
                     sb.receivedAt ASC,
                     sb.id ASC
            """)
    List<StockBatch> findAvailableBatchesBySellingPrice(@Param("branchId") Long branchId, @Param("itemId") Long itemId, @Param("sellingPrice") BigDecimal sellingPrice);

    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId AND sb.quantity > 0
            ORDER BY CASE WHEN sb.sourceType = com.chala.posapp.entity.stock.StockBatchSourceType.PROCESSING THEN 0 ELSE 1 END,
                     sb.receivedAt ASC,
                     sb.id ASC
            """)
    List<StockBatch> findAvailableBatchesForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT sb FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId ORDER BY sb.receivedAt ASC, sb.id ASC")
    List<StockBatch> findBatchesForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT COUNT(sb) > 0 FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId")
    boolean existsBatchForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT COALESCE(SUM(sb.quantity), 0) FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId")
    Integer getTotalQuantityByItemAndScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    List<StockBatch> findAllByBranchId(Long branchId);

    @Query("SELECT sb.item.id AS itemId, sb.item.barcode AS barcode, sb.item.name AS itemName, " +
            "sb.item.altName AS altName, " +
            "SUM(sb.quantity) AS totalQty, sb.item.reorderLevel AS reorderLevel, " +
            "sb.item.itemType AS itemType, sb.item.defaultUnit AS defaultUnit " +
            "FROM StockBatch sb " +
            "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND sb.item.itemType != com.chala.posapp.entity.ItemType.SERVICE " +
            "GROUP BY sb.item.id, sb.item.barcode, sb.item.name, sb.item.altName, sb.item.reorderLevel, sb.item.itemType, sb.item.defaultUnit " +
            "HAVING SUM(sb.quantity) <= sb.item.reorderLevel")
    List<LowStockResponse> findLowStockItems(@Param("branchId") Long branchId);

    @Query(value = "SELECT new com.chala.posapp.dto.stock.StockResponseWithItems(" +
            "sb.item.id, " +
            "sb.item.barcode, " +
            "sb.item.name, " +
            "sb.item.altName, " +
            "sb.item.costPrice, " +
            "sb.item.sellingPrice, " +
            "SUM(sb.quantity), " +
            "sb.item.reorderLevel, " +
            "sb.item.itemType, " +
            "sb.item.defaultUnit, " +
            "c.id, " +
            "c.name, " +
            "sc.id, " +
            "sc.name) " +
            "FROM StockBatch sb " +
            "LEFT JOIN sb.item.subCategory sc " +
            "LEFT JOIN sc.category c " +
            "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND sb.item.itemType != com.chala.posapp.entity.ItemType.SERVICE " +
            "AND (:categoryId IS NULL OR c.id = :categoryId) " +
            "AND (:subCategoryId IS NULL OR sc.id = :subCategoryId) " +
            "AND (:search IS NULL OR :search = '' " +
            "OR LOWER(sb.item.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sb.item.altName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sb.item.barcode) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR CONCAT('', sb.item.id) LIKE CONCAT('%', :search, '%')) " +
            "GROUP BY sb.item.id, sb.item.barcode, sb.item.name, sb.item.altName, sb.item.costPrice, sb.item.sellingPrice, sb.item.reorderLevel, sb.item.itemType, sb.item.defaultUnit, c.id, c.name, sc.id, sc.name " +
            "HAVING (:stockStatus IS NULL OR :stockStatus = 'ALL' " +
            "OR (:stockStatus = 'OUT_OF_STOCK' AND COALESCE(SUM(sb.quantity), 0) <= 0) " +
            "OR (:stockStatus = 'REORDER' AND COALESCE(SUM(sb.quantity), 0) > 0 AND COALESCE(SUM(sb.quantity), 0) <= sb.item.reorderLevel) " +
            "OR (:stockStatus = 'IN_STOCK' AND COALESCE(SUM(sb.quantity), 0) > sb.item.reorderLevel)) " +
            "ORDER BY CASE WHEN COALESCE(SUM(sb.quantity), 0) > 0 THEN 0 ELSE 1 END, sb.item.id DESC",
            countQuery = "SELECT COUNT(DISTINCT i.id) FROM Item i " +
                    "LEFT JOIN i.subCategory sc " +
                    "LEFT JOIN sc.category c " +
                    "WHERE i.itemType != com.chala.posapp.entity.ItemType.SERVICE " +
                    "AND EXISTS (SELECT 1 FROM StockBatch sbx WHERE sbx.item.id = i.id AND (:branchId IS NULL OR sbx.branch.id = :branchId)) " +
                    "AND (:categoryId IS NULL OR c.id = :categoryId) " +
                    "AND (:subCategoryId IS NULL OR sc.id = :subCategoryId) " +
                    "AND (:search IS NULL OR :search = '' " +
                    "OR LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
                    "OR LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%')) " +
                    "OR LOWER(i.barcode) LIKE LOWER(CONCAT('%', :search, '%')) " +
                    "OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
                    "OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
                    "OR CONCAT('', i.id) LIKE CONCAT('%', :search, '%')) " +
                    "AND (:stockStatus IS NULL OR :stockStatus = 'ALL' " +
                    "OR (:stockStatus = 'OUT_OF_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = i.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) <= 0) " +
                    "OR (:stockStatus = 'REORDER' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = i.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > 0 " +
                    "AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = i.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) <= i.reorderLevel) " +
                    "OR (:stockStatus = 'IN_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = i.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > i.reorderLevel))")
    Page<StockResponseWithItems> getStockSummary(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("stockStatus") String stockStatus,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId,
            Pageable pageable);

    @Query("SELECT COALESCE(SUM(" +
            "CASE WHEN sb.item.itemType = com.chala.posapp.entity.ItemType.NORMAL " +
            "OR sb.item.itemType = com.chala.posapp.entity.ItemType.WEIGHT " +
            "OR sb.item.itemType = com.chala.posapp.entity.ItemType.VOLUME " +
            "THEN (sb.quantity / 1000.0) * sb.costPrice " +
            "ELSE sb.quantity * sb.costPrice END), 0) " +
            "FROM StockBatch sb " +
            "LEFT JOIN sb.item.subCategory sc " +
            "LEFT JOIN sc.category c " +
            "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND sb.item.itemType != com.chala.posapp.entity.ItemType.SERVICE " +
            "AND (:categoryId IS NULL OR c.id = :categoryId) " +
            "AND (:subCategoryId IS NULL OR sc.id = :subCategoryId) " +
            "AND (:search IS NULL OR :search = '' " +
            "OR LOWER(sb.item.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sb.item.altName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sb.item.barcode) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR CONCAT('', sb.item.id) LIKE CONCAT('%', :search, '%')) " +
            "AND (:stockStatus IS NULL OR :stockStatus = 'ALL' " +
            "OR (:stockStatus = 'OUT_OF_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = sb.item.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) <= 0) " +
            "OR (:stockStatus = 'REORDER' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = sb.item.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > 0 " +
            "AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = sb.item.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) <= sb.item.reorderLevel) " +
            "OR (:stockStatus = 'IN_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = sb.item.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > sb.item.reorderLevel))")
    BigDecimal getStockValue(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("stockStatus") String stockStatus,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId);

    List<StockBatch> findByBranchIdAndItemId(Long branchId, Long id);

    @Query("SELECT sb FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId ORDER BY sb.receivedAt ASC, sb.id ASC")
    List<StockBatch> findBatchesForBranchItem(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    List<StockBatch> findByItemId(Long id);

    List<StockBatch> findByBranchIdAndBatchCodeStartingWith(Long branchId, String batchCodePrefix);

    Optional<StockBatch> findByBranchIdAndItemIdAndOriginBatchId(Long branchId, Long itemId, Long originBatchId);
}

// BUG-FIX: Removed duplicate StockBatchRepository interface definition that was below.
// Identical to the above — dead code outside the closing brace caused a
// "duplicate class" compile error (same pattern as DashboardRepository BUG-01).
