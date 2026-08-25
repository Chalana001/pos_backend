package com.chala.posapp.repository;

import com.chala.posapp.entity.WarrantyClaim;
import com.chala.posapp.entity.WarrantyClaimStatus;
import com.chala.posapp.dto.warranty.WarrantyClaimListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaim, Long> {

    List<WarrantyClaim> findByWarrantyIdOrderByCreatedAtDesc(Long warrantyId);

    boolean existsByWarrantyIdAndStatusIn(Long warrantyId, Collection<WarrantyClaimStatus> statuses);

    @Query("""
        SELECT new com.chala.posapp.dto.warranty.WarrantyClaimListResponse(
            c.id,
            c.claimNo,
            c.warrantyId,
            c.branchId,
            w.warrantyNo,
            w.invoiceNo,
            w.customerName,
            w.itemName,
            i.altName,
            w.barcode,
            w.status,
            c.actionType,
            c.status,
            c.issueDescription,
            c.receivedAt,
            c.completedAt
        )
        FROM WarrantyClaim c
        JOIN Warranty w ON w.id = c.warrantyId
        LEFT JOIN Item i ON i.id = w.itemId
        WHERE (:branchId IS NULL OR :branchId = 0 OR c.branchId = :branchId)
          AND (:status IS NULL OR c.status = :status)
          AND (:search IS NULL OR :search = ''
               OR LOWER(c.claimNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.warrantyNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.itemName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.barcode) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(i.altName) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<WarrantyClaimListResponse> searchQueue(
            @Param("search") String search,
            @Param("status") WarrantyClaimStatus status,
            @Param("branchId") Long branchId,
            Pageable pageable
    );
}
