package com.chala.posapp.repository;

import com.chala.posapp.entity.PromotionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionAuditRepository extends JpaRepository<PromotionAudit, Long> {

    List<PromotionAudit> findByPromotionIdOrderByAtDescIdDesc(Long promotionId);
}
