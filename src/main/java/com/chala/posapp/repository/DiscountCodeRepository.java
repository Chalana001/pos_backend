package com.chala.posapp.repository;

import com.chala.posapp.entity.DiscountCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<DiscountCode> findAllByOrderByCreatedAtDesc();

    /**
     * Increments in the database rather than read-modify-write, so two concurrent
     * redemptions cannot both see the same usedCount and overshoot maxUses.
     */
    @Modifying
    @Query("update DiscountCode c set c.usedCount = c.usedCount + 1 where c.id = :id")
    void incrementUsage(@Param("id") Long id);
}
