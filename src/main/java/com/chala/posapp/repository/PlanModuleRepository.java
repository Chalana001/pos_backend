package com.chala.posapp.repository;

import com.chala.posapp.entity.PlanModule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanModuleRepository extends JpaRepository<PlanModule, Long> {

    List<PlanModule> findByPlanId(Long planId);

    Optional<PlanModule> findByPlanIdAndModuleKey(Long planId, String moduleKey);

    @Modifying
    @Query("delete from PlanModule pm where pm.planId = :planId")
    void deleteByPlanId(@Param("planId") Long planId);

    @Modifying
    @Query("delete from PlanModule pm where pm.moduleKey = :moduleKey")
    void deleteByModuleKey(@Param("moduleKey") String moduleKey);
}
