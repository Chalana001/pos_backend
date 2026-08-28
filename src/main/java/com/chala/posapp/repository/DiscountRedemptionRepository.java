package com.chala.posapp.repository;

import com.chala.posapp.entity.DiscountRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DiscountRedemptionRepository extends JpaRepository<DiscountRedemption, Long> {

    List<DiscountRedemption> findByCodeIdOrderByRedeemedAtDesc(Long codeId);

    List<DiscountRedemption> findByTenantIdOrderByRedeemedAtDesc(String tenantId);

    long countByCodeIdAndTenantId(Long codeId, String tenantId);

    @Query("select coalesce(sum(r.amountOff), 0) from DiscountRedemption r")
    double totalDiscountGiven();
}
