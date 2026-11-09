package com.chala.posapp.repository;

import com.chala.posapp.dto.stock.ItemStockAggregate;
import com.chala.posapp.dto.stock.ItemStockBatch;
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
import java.time.LocalDateTime;
import java.util.Collection;
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
     * FIFO batch lookup by selling price, also locked to prevent concurrent deduction races.
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

    /**
     * Stock profile batches: include both positive and negative balances, but
     * omit zeroed historical batches.
     */
    @Query("SELECT sb FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND sb.item.id = :itemId AND sb.quantity <> 0 ORDER BY sb.receivedAt ASC, sb.id ASC")
    List<StockBatch> findNonZeroBatchesForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT COUNT(sb) > 0 FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId")
    boolean existsBatchForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT COUNT(sb) > 0 FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND sb.item.id = :itemId AND sb.quantity < 0")
    boolean existsNegativeBatchForScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    @Query("SELECT COALESCE(SUM(sb.quantity), 0) FROM StockBatch sb WHERE (:branchId IS NULL OR sb.branch.id = :branchId) AND sb.item.id = :itemId")
    Integer getTotalQuantityByItemAndScope(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    // ------------------------------------------------------------------
    // Bulk stock aggregation. The predicates mirror the per-item methods
    // above exactly: every stock_batches row is applicable (the legacy stock
    // reads carry no deleted/cancelled/status/source-type filter, and the
    // entity has no such columns), so the aggregate is a plain GROUP BY on
    // item_id. The repository deliberately does not interpret branch
    // semantics — the concrete-branch variant filters to one branch and the
    // all-branch variant omits the branch predicate, which is the same row
    // population the legacy (:branchId IS NULL OR …) scope returns when
    // ItemService passes null for branchId = 0. Passing null as :branchId
    // matches no rows; a null scope is the caller's "skip the stock block"
    // case and must never become all-branch aggregation here.
    //
    // Empty itemIds is safe but not free: Hibernate renders the empty IN
    // list as a query matching no rows (no invalid IN () SQL), so the call
    // returns an empty list after one execution. The project convention for
    // IN-list queries is a service-level empty guard (see
    // PromotionGate.limitStates around PromotionRepository.limitStateRaw);
    // later callers of these methods should guard the same way.
    // ------------------------------------------------------------------

    /**
     * Net quantity, batch count and negative-batch count per item for one
     * concrete branch. Items without batch rows produce no row at all — the
     * caller reads the absence of a row as "never stocked" (net 0, count 0,
     * flag false), the same answer the per-item methods give.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.stock.ItemStockAggregate(
                sb.item.id,
                SUM(sb.quantity),
                COUNT(sb),
                SUM(CASE WHEN sb.quantity < 0 THEN 1 ELSE 0 END))
            FROM StockBatch sb
            WHERE sb.branch.id = :branchId AND sb.item.id IN (:itemIds)
            GROUP BY sb.item.id
            """)
    List<ItemStockAggregate> aggregateStockForBranch(@Param("itemIds") Collection<Long> itemIds, @Param("branchId") Long branchId);

    /**
     * The same aggregate across every branch — what {@code ItemService} calls
     * the all-branch scope ({@code branchId = 0}). Grouped by item ID.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.stock.ItemStockAggregate(
                sb.item.id,
                SUM(sb.quantity),
                COUNT(sb),
                SUM(CASE WHEN sb.quantity < 0 THEN 1 ELSE 0 END))
            FROM StockBatch sb
            WHERE sb.item.id IN (:itemIds)
            GROUP BY sb.item.id
            """)
    List<ItemStockAggregate> aggregateStockAcrossBranches(@Param("itemIds") Collection<Long> itemIds);

    // ------------------------------------------------------------------
    // Bulk positive-batch retrieval. The row population and the per-item
    // order mirror findAvailableBatchesForScope exactly: quantity > 0 only
    // (zero and negative rows excluded), PROCESSING-sourced batches first,
    // then receivedAt ASC, then id ASC. The only addition is item_id as the
    // leading ORDER BY key so one flat list can carry several items while
    // each item's slice keeps the legacy order verbatim — the assembler
    // groups by item id and must keep the returned order within each group.
    //
    // Like the aggregates above: a null :branchId matches no rows (the
    // repository never promotes null to all-branch scope — ItemService skips
    // the stock block for null and calls the all-branch variant for
    // branchId = 0), and empty itemIds safely returns an empty list after
    // one harmless query. Callers guard empty collections upstream per the
    // project convention (PromotionGate.limitStates).
    // ------------------------------------------------------------------

    /**
     * Positive batches of many items for one concrete branch, ordered per
     * item exactly like {@link #findAvailableBatchesForScope}.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.stock.ItemStockBatch(
                sb.item.id, sb.id, sb.sellingPrice, sb.quantity, sb.expireDate, sb.sourceType, sb.receivedAt)
            FROM StockBatch sb
            WHERE sb.branch.id = :branchId AND sb.item.id IN (:itemIds) AND sb.quantity > 0
            ORDER BY sb.item.id ASC,
                     CASE WHEN sb.sourceType = com.chala.posapp.entity.stock.StockBatchSourceType.PROCESSING THEN 0 ELSE 1 END,
                     sb.receivedAt ASC,
                     sb.id ASC
            """)
    List<ItemStockBatch> findAvailableBatchesForBranch(@Param("itemIds") Collection<Long> itemIds, @Param("branchId") Long branchId);

    /**
     * The same positive-batch rows across every branch — what
     * {@code ItemService} calls the all-branch scope ({@code branchId = 0}).
     * Ordering within each item matches
     * {@link #findAvailableBatchesForScope} verbatim.
     */
    @Query("""
            SELECT new com.chala.posapp.dto.stock.ItemStockBatch(
                sb.item.id, sb.id, sb.sellingPrice, sb.quantity, sb.expireDate, sb.sourceType, sb.receivedAt)
            FROM StockBatch sb
            WHERE sb.item.id IN (:itemIds) AND sb.quantity > 0
            ORDER BY sb.item.id ASC,
                     CASE WHEN sb.sourceType = com.chala.posapp.entity.stock.StockBatchSourceType.PROCESSING THEN 0 ELSE 1 END,
                     sb.receivedAt ASC,
                     sb.id ASC
            """)
    List<ItemStockBatch> findAvailableBatchesAcrossBranches(@Param("itemIds") Collection<Long> itemIds);

    List<StockBatch> findAllByBranchId(Long branchId);

    /**
     * FEFO lookup for barcode labels: the sellable batch (quantity > 0) with the
     * earliest expireDate, scoped to a branch (null branchId = all branches).
     * Callers should cap results to 1 via the Pageable (e.g. PageRequest.of(0, 1)).
     */
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE (:branchId IS NULL OR sb.branch.id = :branchId)
              AND sb.item.id = :itemId
              AND sb.quantity > 0
              AND sb.expireDate IS NOT NULL
            ORDER BY sb.expireDate ASC
            """)
    List<StockBatch> findEarliestExpiryBatches(@Param("branchId") Long branchId, @Param("itemId") Long itemId, Pageable pageable);

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
            "AND (:expiryStatus IS NULL OR :expiryStatus = 'ALL' " +
            "OR (:expiryStatus = 'EXPIRED' AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) IS NOT NULL " +
            "AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) < :expiryToday) " +
            "OR (:expiryStatus = 'EXPIRING_SOON' AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) >= :expiryToday " +
            "AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) <= :expiryCutoff) " +
            "OR (:expiryStatus = 'NOT_EXPIRED' AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) IS NOT NULL " +
            "AND MIN(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN sb.expireDate END) > :expiryCutoff) " +
            "OR (:expiryStatus = 'MISSING_EXPIRY' AND SUM(CASE WHEN sb.quantity > 0 THEN 1 ELSE 0 END) > 0 " +
            "AND SUM(CASE WHEN sb.quantity > 0 AND sb.expireDate IS NOT NULL THEN 1 ELSE 0 END) = 0)) " +
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
                    "OR (:stockStatus = 'IN_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = i.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > i.reorderLevel)) " +
                    "AND (:expiryStatus IS NULL OR :expiryStatus = 'ALL' " +
                    "OR (:expiryStatus = 'EXPIRED' AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) IS NOT NULL " +
                    "AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) < :expiryToday) " +
                    "OR (:expiryStatus = 'EXPIRING_SOON' AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) IS NOT NULL " +
                    "AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) >= :expiryToday " +
                    "AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) <= :expiryCutoff) " +
                    "OR (:expiryStatus = 'NOT_EXPIRED' AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) IS NOT NULL " +
                    "AND (SELECT MIN(sbx2.expireDate) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) > :expiryCutoff) " +
                    "OR (:expiryStatus = 'MISSING_EXPIRY' AND (SELECT COUNT(sbx2) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0) > 0 " +
                    "AND (SELECT COUNT(sbx2) FROM StockBatch sbx2 WHERE sbx2.item.id = i.id AND (:branchId IS NULL OR sbx2.branch.id = :branchId) AND sbx2.quantity > 0 AND sbx2.expireDate IS NOT NULL) = 0))")
    Page<StockResponseWithItems> getStockSummary(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("stockStatus") String stockStatus,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId,
            @Param("expiryStatus") String expiryStatus,
            @Param("expiryToday") LocalDateTime expiryToday,
            @Param("expiryCutoff") LocalDateTime expiryCutoff,
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
            "OR (:stockStatus = 'IN_STOCK' AND (SELECT COALESCE(SUM(sb2.quantity), 0) FROM StockBatch sb2 WHERE sb2.item.id = sb.item.id AND (:branchId IS NULL OR sb2.branch.id = :branchId)) > sb.item.reorderLevel)) " +
            "AND (:expiryStatus IS NULL OR :expiryStatus = 'ALL' " +
            "OR (:expiryStatus = 'EXPIRED' AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) IS NOT NULL " +
            "AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) < :expiryToday) " +
            "OR (:expiryStatus = 'EXPIRING_SOON' AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) IS NOT NULL " +
            "AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) >= :expiryToday " +
            "AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) <= :expiryCutoff) " +
            "OR (:expiryStatus = 'NOT_EXPIRED' AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) IS NOT NULL " +
            "AND (SELECT MIN(sb3.expireDate) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) > :expiryCutoff) " +
            "OR (:expiryStatus = 'MISSING_EXPIRY' AND (SELECT COUNT(sb3) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0) > 0 " +
            "AND (SELECT COUNT(sb3) FROM StockBatch sb3 WHERE sb3.item.id = sb.item.id AND (:branchId IS NULL OR sb3.branch.id = :branchId) AND sb3.quantity > 0 AND sb3.expireDate IS NOT NULL) = 0)) ")
    BigDecimal getStockValue(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("stockStatus") String stockStatus,
            @Param("categoryId") Long categoryId,
            @Param("subCategoryId") Long subCategoryId,
            @Param("expiryStatus") String expiryStatus,
            @Param("expiryToday") LocalDateTime expiryToday,
            @Param("expiryCutoff") LocalDateTime expiryCutoff);

    List<StockBatch> findByBranchIdAndItemId(Long branchId, Long id);

    @Query("SELECT sb FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId ORDER BY sb.receivedAt ASC, sb.id ASC")
    List<StockBatch> findBatchesForBranchItem(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    /**
     * Returns the branch/item ledger rows under a write lock. Locking every
     * existing row gives concurrent checkouts one serialization point even when
     * the ledger currently has no negative row and an override batch is about to
     * be created.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.branch.id = :branchId AND sb.item.id = :itemId
            ORDER BY sb.receivedAt ASC, sb.id ASC
            """)
    List<StockBatch> findBatchesForBranchItemForUpdate(
            @Param("branchId") Long branchId,
            @Param("itemId") Long itemId
    );

    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.branch.id = :branchId
              AND sb.item.id = :itemId
              AND sb.quantity < 0
            ORDER BY sb.receivedAt ASC, sb.id ASC
            """)
    List<StockBatch> findNegativeBatchesForBranchItem(
            @Param("branchId") Long branchId,
            @Param("itemId") Long itemId
    );

    List<StockBatch> findByItemId(Long id);

    List<StockBatch> findByBranchIdAndBatchCodeStartingWith(Long branchId, String batchCodePrefix);

    Optional<StockBatch> findByBranchIdAndItemIdAndOriginBatchId(Long branchId, Long itemId, Long originBatchId);
}

// BUG-FIX: Removed duplicate StockBatchRepository interface definition that was below.
// Identical to the above, dead code outside the closing brace caused a
// "duplicate class" compile error (same pattern as DashboardRepository BUG-01).
