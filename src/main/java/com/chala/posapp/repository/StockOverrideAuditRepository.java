package com.chala.posapp.repository;

import com.chala.posapp.entity.StockOverrideAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockOverrideAuditRepository extends JpaRepository<StockOverrideAudit, Long> {
}
