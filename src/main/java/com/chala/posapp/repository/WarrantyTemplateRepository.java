package com.chala.posapp.repository;

import com.chala.posapp.entity.WarrantyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarrantyTemplateRepository extends JpaRepository<WarrantyTemplate, Long> {
    List<WarrantyTemplate> findAllByOrderByPeriodValueAscCreatedAtAsc();

    List<WarrantyTemplate> findByActiveTrueOrderByPeriodValueAscCreatedAtAsc();

    Optional<WarrantyTemplate> findByLabelIgnoreCase(String label);
}
