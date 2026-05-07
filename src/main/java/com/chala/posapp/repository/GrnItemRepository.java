package com.chala.posapp.repository;

import com.chala.posapp.dto.stock.StockPurchaseHistoryResponse;
import com.chala.posapp.entity.GRN;
import com.chala.posapp.entity.GrnItem;
import com.chala.posapp.entity.PurchaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface GrnItemRepository extends JpaRepository<GrnItem, Long> {

    List<GrnItem> findByGrn(GRN grn);

    List<GrnItem> findByGrnId(Long grnId);

    @Query(
            value = """
                    SELECT new com.chala.posapp.dto.stock.StockPurchaseHistoryResponse(
                        p.id,
                        p.invoiceNo,
                        p.supplier.name,
                        g.branch.id,
                        g.branch.name,
                        g.grnNo,
                        gi.displayQty,
                        gi.qtyUnit,
                        gi.costPrice,
                        gi.sellingPrice,
                        gi.amount,
                        g.receivedAt,
                        p.status
                    )
                    FROM GrnItem gi
                    JOIN gi.grn g
                    JOIN g.purchase p
                    WHERE gi.item.id = :itemId
                      AND (:branchId IS NULL OR g.branch.id = :branchId)
                      AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
                      AND (:status IS NULL OR p.status = :status)
                      AND (:fromDateTime IS NULL OR g.receivedAt >= :fromDateTime)
                      AND (:toDateTime IS NULL OR g.receivedAt <= :toDateTime)
                      AND (
                          :search IS NULL
                          OR TRIM(:search) = ''
                          OR LOWER(p.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(p.supplier.name) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(g.grnNo) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                    ORDER BY g.receivedAt DESC, p.id DESC, gi.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(gi.id)
                    FROM GrnItem gi
                    JOIN gi.grn g
                    JOIN g.purchase p
                    WHERE gi.item.id = :itemId
                      AND (:branchId IS NULL OR g.branch.id = :branchId)
                      AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
                      AND (:status IS NULL OR p.status = :status)
                      AND (:fromDateTime IS NULL OR g.receivedAt >= :fromDateTime)
                      AND (:toDateTime IS NULL OR g.receivedAt <= :toDateTime)
                      AND (
                          :search IS NULL
                          OR TRIM(:search) = ''
                          OR LOWER(p.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(p.supplier.name) LIKE LOWER(CONCAT('%', :search, '%'))
                          OR LOWER(g.grnNo) LIKE LOWER(CONCAT('%', :search, '%'))
                      )
                    """
    )
    Page<StockPurchaseHistoryResponse> findPurchaseHistoryByItem(
            @Param("branchId") Long branchId,
            @Param("itemId") Long itemId,
            @Param("search") String search,
            @Param("supplierId") Long supplierId,
            @Param("status") PurchaseStatus status,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );
}
