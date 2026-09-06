package com.chala.posapp.repository;

import com.chala.posapp.entity.LoyaltySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltySettingsRepository extends JpaRepository<LoyaltySettings, Long> {
    /** One row per tenant database; the lowest id wins should a race ever make two. */
    Optional<LoyaltySettings> findFirstByOrderByIdAsc();
}
