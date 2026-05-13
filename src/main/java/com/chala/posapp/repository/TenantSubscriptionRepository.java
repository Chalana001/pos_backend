package com.chala.posapp.repository;

import com.chala.posapp.entity.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, Long>, JpaSpecificationExecutor<TenantSubscription> {

    Optional<TenantSubscription> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);

    boolean existsByPlanId(Long planId);

    List<TenantSubscription> findByIsActiveTrue();

    List<TenantSubscription> findByValidUntilBefore(LocalDateTime now);

    boolean existsByTenantIdAndIsActiveTrueAndValidUntilAfter(String tenantId, LocalDateTime now);

    long countByIsActiveTrueAndBlockedFalseAndValidUntilAfter(LocalDateTime now);

    long countByValidUntilBefore(LocalDateTime now);

    List<TenantSubscription> findAllByOrderByCreatedAtDesc();
}
