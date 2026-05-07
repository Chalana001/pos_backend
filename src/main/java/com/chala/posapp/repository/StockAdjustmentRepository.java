package com.chala.posapp.repository;

import com.chala.posapp.entity.stock.StockAdjustment;
import com.chala.posapp.entity.stock.StockAdjustmentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long> {

    List<StockAdjustment> findByBranchIdOrderByCreatedAtDesc(Long branchId);

    List<StockAdjustment> findByBranchIdAndItemIdOrderByCreatedAtDesc(Long branchId, Long itemId);

    @Query("""
            SELECT sa
            FROM StockAdjustment sa
            WHERE (:branchId IS NULL OR sa.branchId = :branchId)
              AND (:type IS NULL OR sa.type = :type)
              AND (:userId IS NULL OR sa.userId = :userId)
              AND (:fromDateTime IS NULL OR sa.createdAt >= :fromDateTime)
              AND (:toDateTime IS NULL OR sa.createdAt <= :toDateTime)
              AND (
                  :search IS NULL
                  OR TRIM(:search) = ''
                  OR LOWER(sa.reason) LIKE LOWER(CONCAT('%', :search, '%'))
                  OR EXISTS (
                      SELECT 1
                      FROM Item i
                      WHERE i.id = sa.itemId
                        AND (
                            LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(i.barcode) LIKE LOWER(CONCAT('%', :search, '%'))
                        )
                  )
              )
            ORDER BY sa.createdAt DESC, sa.id DESC
            """)
    Page<StockAdjustment> findHistory(
            @Param("branchId") Long branchId,
            @Param("search") String search,
            @Param("type") StockAdjustmentType type,
            @Param("userId") Long userId,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTime") LocalDateTime toDateTime,
            Pageable pageable
    );
}
