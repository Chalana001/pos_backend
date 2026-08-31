package com.chala.posapp.repository;

import com.chala.posapp.entity.PurchaseReturnItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PurchaseReturnItemRepository extends JpaRepository<PurchaseReturnItem, Long> {

    List<PurchaseReturnItem> findByPurchaseReturnId(Long purchaseReturnId);

    List<PurchaseReturnItem> findByGrnItemId(Long grnItemId);

    /** Total qty already returned for a specific GRN item across all returns */
    @Query("SELECT COALESCE(SUM(ri.returnQty), 0) FROM PurchaseReturnItem ri WHERE ri.grnItemId = :grnItemId")
    int sumReturnedQtyByGrnItemId(@Param("grnItemId") Long grnItemId);
}
