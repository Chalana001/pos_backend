package com.chala.posapp.repository;

import com.chala.posapp.entity.BillingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface BillingRecordRepository extends JpaRepository<BillingRecord, Long> {

    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM BillingRecord b WHERE b.createdAt BETWEEN :from AND :to")
    double totalAmountBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    java.util.List<BillingRecord> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
