package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.StockTransfer;
import com.chala.posapp.entity.stock.StockTransferStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    Optional<StockTransfer> findByTransferNo(String transferNo);

    List<StockTransfer> findByFromBranchIdOrderByRequestedAtDesc(Long fromBranchId);

    List<StockTransfer> findByToBranchIdOrderByRequestedAtDesc(Long toBranchId);

    List<StockTransfer> findByToBranchIdAndStatusOrderByRequestedAtDesc(Long toBranchId, StockTransferStatus status);

    List<StockTransfer> findByFromBranchIdAndStatusOrderByRequestedAtDesc(Long fromBranchId, StockTransferStatus status);

    long countByFromBranchId(Long fromBranchId);

    @Query("""
            SELECT st
            FROM StockTransfer st
            WHERE (:branchId IS NULL OR st.fromBranchId = :branchId)
              AND (:status IS NULL OR st.status = :status)
              AND (:fromDateTime IS NULL OR st.requestedAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR st.requestedAt <= :toDateTime)
              AND (
                  :search IS NULL
                  OR TRIM(:search) = ''
                  OR LOWER(st.transferNo) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(COALESCE(st.note, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR EXISTS (
                      SELECT 1
                      FROM StockTransferItem sti
                      LEFT JOIN Item i ON i.id = sti.itemId
                      WHERE sti.transferId = st.id
                        AND (
                            LOWER(sti.itemName) LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(sti.barcode) LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%'))
                        )
                  )
              )
            ORDER BY st.requestedAt DESC, st.id DESC
            """)
    Page<StockTransfer> findOutgoingHistory(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("status") StockTransferStatus status,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );

    @Query("""
            SELECT st
            FROM StockTransfer st
            WHERE (:branchId IS NULL OR st.toBranchId = :branchId)
              AND (:status IS NULL OR st.status = :status)
              AND (:fromDateTime IS NULL OR st.requestedAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR st.requestedAt <= :toDateTime)
              AND (
                  :search IS NULL
                  OR TRIM(:search) = ''
                  OR LOWER(st.transferNo) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(COALESCE(st.note, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR EXISTS (
                      SELECT 1
                      FROM StockTransferItem sti
                      LEFT JOIN Item i ON i.id = sti.itemId
                      WHERE sti.transferId = st.id
                        AND (
                            LOWER(sti.itemName) LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(sti.barcode) LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%'))
                        )
                  )
              )
            ORDER BY st.requestedAt DESC, st.id DESC
            """)
    Page<StockTransfer> findIncomingHistory(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("status") StockTransferStatus status,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );

    // RPT-10: Stock transfer report — all transfers for a date range
    @Query("""
        SELECT st FROM StockTransfer st
        WHERE (:fromBranchId IS NULL OR st.fromBranchId = :fromBranchId)
          AND (:toBranchId   IS NULL OR st.toBranchId   = :toBranchId)
          AND (:status       IS NULL OR st.status        = :status)
          AND st.requestedAt BETWEEN :fromDate AND :toDate
        ORDER BY st.requestedAt DESC
        """)
    Page<StockTransfer> findForReport(
            @Param("fromBranchId") Long fromBranchId,
            @Param("toBranchId")   Long toBranchId,
            @Param("status")       StockTransferStatus status,
            @Param("fromDate")     LocalDateTime fromDate,
            @Param("toDate")       LocalDateTime toDate,
            Pageable pageable);
}
