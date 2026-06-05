package com.chala.posapp.repository;

import com.chala.posapp.entity.AppConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppConfigurationRepository extends JpaRepository<AppConfiguration, Long> {
    Optional<AppConfiguration> findFirstByOrderByIdAsc();
    Optional<AppConfiguration> findByTenantIdAndBranchId(String tenantId, Long branchId);
    Optional<AppConfiguration> findByTenantIdAndBranchIdIsNull(String tenantId);
}
