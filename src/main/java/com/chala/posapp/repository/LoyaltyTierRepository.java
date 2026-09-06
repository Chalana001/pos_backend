package com.chala.posapp.repository;

import com.chala.posapp.entity.LoyaltyTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTierRepository extends JpaRepository<LoyaltyTier, Long> {
    List<LoyaltyTier> findByActiveTrueOrderByMinLifetimePointsAsc();
    List<LoyaltyTier> findAllByOrderByMinLifetimePointsAsc();
}
