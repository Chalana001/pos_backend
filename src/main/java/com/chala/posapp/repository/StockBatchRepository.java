package com.chala.posapp.repository;

import com.chala.posapp.dto.stock.LowStockResponse;
import com.chala.posapp.dto.stock.StockResponseWithItems;
import com.chala.posapp.entity.stock.StockBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    @Query("SELECT COALESCE(SUM(sb.quantity), 0) FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId")
    Integer getTotalQuantityByItemAndBranch(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    List<StockBatch> findByBranchIdAndItemIdAndQuantityGreaterThanOrderByIdAsc(Long branchId, Long itemId, Double quantity);

    @Query("SELECT sb FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId AND sb.quantity > 0 ORDER BY sb.receivedAt ASC")
    List<StockBatch> findAvailableBatches(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    List<StockBatch> findAllByBranchId(Long branchId);

    @Query("SELECT sb.item.id AS itemId, sb.item.name AS itemName, SUM(sb.quantity) AS totalQty, sb.item.reorderLevel AS reorderLevel " +
            "FROM StockBatch sb " +
            "WHERE sb.branch.id = :branchId " +
            "GROUP BY sb.item.id, sb.item.name, sb.item.reorderLevel " +
            "HAVING SUM(sb.quantity) <= sb.item.reorderLevel")
    List<LowStockResponse> findLowStockItems(@Param("branchId") Long branchId);

    @Query(value = "SELECT new com.chala.posapp.dto.stock.StockResponseWithItems(" +
            "sb.item.id, " +
            "sb.item.barcode, " +
            "sb.item.name, " +
            "sb.item.costPrice, " +
            "sb.item.sellingPrice, " +
            "SUM(sb.quantity)) " +
            "FROM StockBatch sb " +
            "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
            "AND (:search IS NULL OR :search = '' OR LOWER(sb.item.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(sb.item.barcode) LIKE LOWER(CONCAT('%', :search, '%'))) " +
            "GROUP BY sb.item.id, sb.item.barcode, sb.item.name, sb.item.costPrice, sb.item.sellingPrice",

            countQuery = "SELECT COUNT(DISTINCT sb.item.id) FROM StockBatch sb " +
                    "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " +
                    "AND (:search IS NULL OR :search = '' OR LOWER(sb.item.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(sb.item.barcode) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<StockResponseWithItems> getStockSummary(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            Pageable pageable);

    List<StockBatch> findByBranchIdAndItemId(Long branchId, Long id);

    List<StockBatch> findByItemId(Long id);
}