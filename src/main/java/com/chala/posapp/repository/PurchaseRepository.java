package com.chala.posapp.repository;

import com.chala.posapp.entity.Purchase;
import com.chala.posapp.entity.PurchaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    Page<Purchase> findAllByOrderByIdDesc(Pageable pageable);
    List<Purchase> findAllByOrderByIdDesc();

    @Query("""
            SELECT p
            FROM Purchase p
            WHERE (:supplierId IS NULL OR p.supplier.id = :supplierId)
              AND (:status IS NULL OR p.status = :status)
              AND (:fromDateTime IS NULL OR p.createdAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR p.createdAt <= :toDateTime)
              AND (
                  :search IS NULL
                  OR TRIM(:search) = ''
                  OR LOWER(p.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR LOWER(p.supplier.name) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR EXISTS (
                      SELECT 1
                      FROM GRN g
                      WHERE g.purchase = p
                        AND LOWER(g.grnNo) LIKE LOWER(CONCAT('%', :search, '%'))
                  )
              )
              AND (
                  :managerBranchId IS NULL
                  OR NOT EXISTS (
                      SELECT 1
                      FROM GRN restrictedGrn
                      WHERE restrictedGrn.purchase = p
                        AND restrictedGrn.branch.id <> :managerBranchId
                  )
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    Page<Purchase> findHistory(
            @Param("search") String search,
            @Param("supplierId") Long supplierId,
            @Param("status") PurchaseStatus status,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            @Param("managerBranchId") Long managerBranchId,
            Pageable pageable
    );
}
