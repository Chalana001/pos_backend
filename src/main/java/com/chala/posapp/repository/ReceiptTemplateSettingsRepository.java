package com.chala.posapp.repository;

import com.chala.posapp.entity.PrintTemplateType;
import com.chala.posapp.entity.ReceiptTemplateSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceiptTemplateSettingsRepository extends JpaRepository<ReceiptTemplateSettings, Long> {
    Optional<ReceiptTemplateSettings> findByBranchIdAndTemplateType(Long branchId, PrintTemplateType templateType);
}
