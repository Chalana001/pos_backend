package com.chala.posapp.repository;

import com.chala.posapp.entity.Warranty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarrantyRepository extends JpaRepository<Warranty, Long> {

    List<Warranty> findByOrderId(Long orderId);

    @Query("""
        SELECT w
        FROM Warranty w
        WHERE (:branchId IS NULL OR w.branchId = :branchId)
          AND (:search IS NULL OR :search = ''
               OR LOWER(w.warrantyNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.itemName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(w.barcode) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<Warranty> search(
            @Param("search") String search,
            @Param("branchId") Long branchId,
            Pageable pageable
    );
}
