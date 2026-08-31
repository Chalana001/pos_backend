package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.StockProcessing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface StockProcessingRepository extends JpaRepository<StockProcessing, Long> {

    @Query("""
            SELECT sp FROM StockProcessing sp
            WHERE (:branchId IS NULL OR :branchId = 0 OR sp.branch.id = :branchId)
              AND (:sourceItemId IS NULL OR sp.sourceItem.id = :sourceItemId)
              AND (:fromDate IS NULL OR sp.processedAt >= :fromDate)
              AND (:toDate IS NULL OR sp.processedAt <= :toDate)
            ORDER BY sp.processedAt DESC, sp.id DESC
            """)
    Page<StockProcessing> findHistory(
            @Param("branchId") Long branchId,
            @Param("sourceItemId") Long sourceItemId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable
    );
}
