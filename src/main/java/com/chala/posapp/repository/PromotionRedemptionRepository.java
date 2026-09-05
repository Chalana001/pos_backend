package com.chala.posapp.repository;

import com.chala.posapp.entity.PromotionRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, Long> {

    List<PromotionRedemption> findByOrderIdAndReversedAtIsNull(Long orderId);

    /** Orders — not lines — on which this promotion has been given to this customer and not reversed. */
    @Query("SELECT COUNT(DISTINCT r.orderId) FROM PromotionRedemption r " +
           "WHERE r.promotionId = :promotionId AND r.customerId = :customerId AND r.reversedAt IS NULL")
    long countOrdersForCustomer(@Param("promotionId") Long promotionId, @Param("customerId") Long customerId);

    @Query("SELECT COUNT(DISTINCT r.orderId) FROM PromotionRedemption r " +
           "WHERE r.promotionCodeId = :codeId AND r.customerId = :customerId AND r.reversedAt IS NULL")
    long countOrdersForCodeAndCustomer(@Param("codeId") Long codeId, @Param("customerId") Long customerId);

    @Modifying
    @Query("UPDATE PromotionRedemption r SET r.reversedAt = :at, r.reversalOrderId = :reversalOrderId " +
           "WHERE r.orderId = :orderId AND r.reversedAt IS NULL")
    int reverseForOrder(@Param("orderId") Long orderId, @Param("reversalOrderId") Long reversalOrderId,
                        @Param("at") LocalDateTime at);
}
