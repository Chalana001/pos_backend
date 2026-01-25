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

    // Bulk Validation සඳහා (එක පාර Barcode ගොඩක් check කරන්න)
    List<Item> findAllByBarcodeIn(List<String> barcodes);

    List<Item> findByNameContainingIgnoreCase(String name);

    // ✅ 1. Branch Stock Query (Updated for StockBatch)
    // stock table එක වෙනුවට stock_batches පාවිච්චි කරලා SUM එක ගන්නවා.
    // LEFT JOIN දැම්මා, එතකොට Stock නැති (0 තියෙන) Items වුනත් List එකේ පෙන්නනවා.
    @Query(value = """
            SELECT 
                i.id, i.barcode, i.name, i.category,
                i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at,
                COALESCE(SUM(sb.quantity), 0) AS qty
            FROM items i
            LEFT JOIN stock_batches sb 
                ON sb.item_id = i.id AND sb.branch_id = :branchId
            GROUP BY i.id, i.barcode, i.name, i.category, i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at
            ORDER BY i.id DESC
            """, nativeQuery = true)
    List<Object[]> itemsWithBranchStockRaw(@Param("branchId") Long branchId);

    // ✅ 2. Total Stock Query (All Branches)
    // මෙතන කලින් code එකේ i.created_at මග ඇරිලා තිබුනා. එකත් දැම්මා (index 8).
    @Query(value = """
            SELECT 
                i.id, i.barcode, i.name, i.category,
                i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at,
                COALESCE(SUM(sb.quantity), 0) AS qty
            FROM items i
            LEFT JOIN stock_batches sb ON sb.item_id = i.id
            GROUP BY i.id, i.barcode, i.name, i.category, i.cost_price, i.selling_price, i.reorder_level, i.active, i.created_at
            ORDER BY i.id DESC
            """, nativeQuery = true)
    List<Object[]> itemsWithTotalStockRaw();
}