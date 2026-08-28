package com.chala.posapp.repository;

import com.chala.posapp.entity.TenantModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TenantModuleRepository extends JpaRepository<TenantModule, Long> {

    List<TenantModule> findByTenantId(String tenantId);

    Optional<TenantModule> findByTenantIdAndModuleKey(String tenantId, String moduleKey);

    long countByTenantId(String tenantId);

    @Modifying
    @Query("delete from TenantModule tm where tm.tenantId = :tenantId")
    void deleteByTenantId(@Param("tenantId") String tenantId);

    @Modifying
    @Query("delete from TenantModule tm where tm.tenantId = :tenantId and tm.moduleKey = :moduleKey")
    void deleteByTenantIdAndModuleKey(@Param("tenantId") String tenantId, @Param("moduleKey") String moduleKey);

    /** How many shops deviate from their plan, per module — drives the "custom" column in the panel. */
    @Query("select tm.moduleKey, count(tm) from TenantModule tm group by tm.moduleKey")
    List<Object[]> countOverridesByModule();

    /**
     * Override count per shop, for the shop list's "custom" badge. One query for the whole page
     * instead of one per row.
     */
    @Query("select tm.tenantId, count(tm) from TenantModule tm group by tm.tenantId")
    List<Object[]> countOverridesByTenant();

    @Query("select count(distinct tm.tenantId) from TenantModule tm")
    long countDistinctTenants();
}
