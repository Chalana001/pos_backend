package com.chala.posapp.repository;

import com.chala.posapp.entity.PromotionSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PromotionSettingsRepository extends JpaRepository<PromotionSettings, Long> {

    /** One row per tenant database; the lowest id is the one, should a race ever make two. */
    Optional<PromotionSettings> findFirstByOrderByIdAsc();
}
