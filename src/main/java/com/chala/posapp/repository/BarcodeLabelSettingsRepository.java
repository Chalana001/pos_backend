package com.chala.posapp.repository;

import com.chala.posapp.entity.BarcodeLabelSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BarcodeLabelSettingsRepository extends JpaRepository<BarcodeLabelSettings, Long> {
    Optional<BarcodeLabelSettings> findByBranchId(Long branchId);
}
