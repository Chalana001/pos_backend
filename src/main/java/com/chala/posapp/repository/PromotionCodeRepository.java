package com.chala.posapp.repository;

import com.chala.posapp.entity.PromotionCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PromotionCodeRepository extends JpaRepository<PromotionCode, Long> {

    Optional<PromotionCode> findByCodeIgnoreCase(String code);

    List<PromotionCode> findByPromotionIdOrderByIdAsc(Long promotionId);

    boolean existsByCodeIgnoreCase(String code);

    /**
     * Takes one redemption, or none. The WHERE clause is the whole point: two tills consuming the
     * last use of a single-use code both run this, exactly one sees a row count of 1, and the
     * other's sale is refused instead of a code being redeemed twice.
     */
    @Modifying
    @Query("UPDATE PromotionCode c SET c.redemptionsUsed = c.redemptionsUsed + 1 " +
           "WHERE c.id = :id AND (c.maxRedemptions IS NULL OR c.redemptionsUsed < c.maxRedemptions)")
    int consume(@Param("id") Long id);

    /** Gives a redemption back on refund. Floors at zero so a double reversal cannot go negative. */
    @Modifying
    @Query("UPDATE PromotionCode c SET c.redemptionsUsed = CASE WHEN c.redemptionsUsed > 0 THEN c.redemptionsUsed - 1 ELSE 0 END " +
           "WHERE c.id = :id")
    int release(@Param("id") Long id);
}
