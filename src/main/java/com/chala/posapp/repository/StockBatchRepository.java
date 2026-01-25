package com.chala.posapp.repository;

import com.chala.posapp.dto.LowStockResponse;
import com.chala.posapp.dto.StockResponseWithItems;
import com.chala.posapp.entity.StockBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    // 1. [පරණ එක] එක Item එකක මුළු Stock එක (බිල් දාද්දි check කරන්න)
    @Query("SELECT COALESCE(SUM(sb.quantity), 0) FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId")
    Integer getTotalQuantityByItemAndBranch(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    // 2. [පරණ එක] විකුණන්න සුදුසු Batches ටික (FIFO - පරණ ඒව මුලින්)
    @Query("SELECT sb FROM StockBatch sb WHERE sb.branch.id = :branchId AND sb.item.id = :itemId AND sb.quantity > 0 ORDER BY sb.receivedAt ASC")
    List<StockBatch> findAvailableBatches(@Param("branchId") Long branchId, @Param("itemId") Long itemId);

    // --- අලුත් METHODS ---

    // 3. Branch එකක තියෙන ඔක්කොම Stock Batches ටික ගන්න (Stock Report එකට)
    // මේකෙන් එන්නේ හැම Batch එකම වෙන වෙනම.
    List<StockBatch> findAllByBranchId(Long branchId);

    // 4. Branch එකේ Low Stock Items ටික හොයන Query එක (Advanced)
    // මේකෙන් කරන්නේ: Branch එකේ තියෙන Item එකේ ඔක්කොම Batch වල එකතුව (SUM) අරගෙන,
    // ඒක Item එකේ Reorder Level එකට වඩා අඩුයිද බලනවා.
    @Query("SELECT sb.item.id AS itemId, sb.item.name AS itemName, SUM(sb.quantity) AS totalQty, sb.item.reorderLevel AS reorderLevel " +
            "FROM StockBatch sb " +
            "WHERE sb.branch.id = :branchId " +
            "GROUP BY sb.item.id, sb.item.name, sb.item.reorderLevel " +
            "HAVING SUM(sb.quantity) <= sb.item.reorderLevel")
    List<LowStockResponse> findLowStockItems(@Param("branchId") Long branchId);

    @Query("SELECT new com.chala.posapp.dto.StockResponseWithItems(" +
            "sb.item.id, " +
            "sb.item.barcode, " +
            "sb.item.name, " +
            "sb.item.costPrice, " +
            "sb.item.sellingPrice, " +
            "SUM(sb.quantity)) " + // මෙතන SUM එක dynamic විදියට හැදෙනවා
            "FROM StockBatch sb " +
            "WHERE (:branchId IS NULL OR sb.branch.id = :branchId) " + // <--- MAGIC PART IS HERE
            "GROUP BY sb.item.id, sb.item.barcode, sb.item.name, sb.item.costPrice, sb.item.sellingPrice")
    List<StockResponseWithItems> getStockSummary(@Param("branchId") Long branchId);
}