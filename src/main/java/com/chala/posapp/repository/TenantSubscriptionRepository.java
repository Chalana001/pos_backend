package com.chala.posapp.repository;

import com.chala.posapp.entity.TenantSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscription, Long> {

    Optional<TenantSubscription> findByTenantId(String tenantId);

    List<TenantSubscription> findByIsActiveTrue();

    List<TenantSubscription> findByValidUntilBefore(LocalDateTime now);

    boolean existsByTenantIdAndIsActiveTrueAndValidUntilAfter(String tenantId, LocalDateTime now);
}