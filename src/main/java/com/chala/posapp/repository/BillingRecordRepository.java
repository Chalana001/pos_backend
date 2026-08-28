package com.chala.posapp.repository;

import com.chala.posapp.entity.BillingRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BillingRecordRepository
        extends JpaRepository<BillingRecord, Long>, JpaSpecificationExecutor<BillingRecord> {

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM BillingRecord b WHERE b.createdAt BETWEEN :from AND :to")
    double totalAmountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    List<BillingRecord> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Page<BillingRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Revenue per calendar month, for the panel's trend chart. Returns rows of
     * {year, month, total, count} so the caller does not have to pull every record to add them up.
     */
    @Query("""
            SELECT YEAR(b.createdAt), MONTH(b.createdAt), COALESCE(SUM(b.amount), 0), COUNT(b)
            FROM BillingRecord b
            WHERE b.createdAt >= :from
            GROUP BY YEAR(b.createdAt), MONTH(b.createdAt)
            ORDER BY YEAR(b.createdAt), MONTH(b.createdAt)
            """)
    List<Object[]> monthlyTotalsSince(@Param("from") LocalDateTime from);

    /** Revenue split by what the money was for. Rows of {actionType, total, count}. */
    @Query("""
            SELECT b.actionType, COALESCE(SUM(b.amount), 0), COUNT(b)
            FROM BillingRecord b
            WHERE b.createdAt BETWEEN :from AND :to
            GROUP BY b.actionType
            """)
    List<Object[]> totalsByActionType(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Lifetime value per shop. Rows of {tenantId, shopName, total, count}. */
    @Query("""
            SELECT b.tenantId, MAX(b.shopName), COALESCE(SUM(b.amount), 0), COUNT(b)
            FROM BillingRecord b
            GROUP BY b.tenantId
            ORDER BY COALESCE(SUM(b.amount), 0) DESC
            """)
    List<Object[]> lifetimeValueByTenant(Pageable pageable);

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM BillingRecord b WHERE b.tenantId = :tenantId")
    double lifetimeValueFor(@Param("tenantId") String tenantId);
}
