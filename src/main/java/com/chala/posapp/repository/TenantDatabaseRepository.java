package com.chala.posapp.repository;

import com.chala.posapp.entity.TenantDatabase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantDatabaseRepository extends JpaRepository<TenantDatabase, Long> {
    Optional<TenantDatabase> findByTenantId(String tenantId);
    boolean existsByTenantId(String tenantId);
}
