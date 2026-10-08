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
     * How many codes gate a promotion, without loading them. List rows used to touch the
     * {@code codes} relation just to size it, which pulled every code of every campaign into
     * the persistence context on one admin screen load; a promotion only ever needs the count.
     */
    long countByPromotionId(Long promotionId);

    /**
     * The same count for a page of campaigns in one grouped query. Rows are [promotionId, count]
     * pairs; campaigns with no codes are simply absent, callers read a missing key as zero.
     */
    @Query("SELECT c.promotion.id, COUNT(c) FROM PromotionCode c WHERE c.promotion.id IN :ids GROUP BY c.promotion.id")
    List<Object[]> countByPromotionIdIn(@Param("ids") List<Long> ids);

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
