package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionScope;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A promotion as the pricing engine sees it: plain values, no entity, no session.
 *
 * <p>This is the shape that gets cached between requests, and the shape an offline till would
 * be sent. Neither can hold a JPA entity — a cached entity is detached and any association not
 * already loaded throws on first touch, and a till has no JPA at all.
 */
public record PromotionSnapshot(
        Long id,
        String name,
        PromotionScope scope,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal minBillAmount,
        BigDecimal maxDiscountAmount,
        LocalDateTime startAt,
        LocalDateTime endAt,
        Long branchId,
        int priority,
        BigDecimal marginFloorPercent,
        boolean allowBelowCost,
        List<TargetSnapshot> targets
) {

    public static PromotionSnapshot from(Promotion promotion) {
        List<TargetSnapshot> targets = promotion.getTargets() == null
                ? List.of()
                : promotion.getTargets().stream().map(TargetSnapshot::from).toList();
        return new PromotionSnapshot(
                promotion.getId(),
                promotion.getName(),
                promotion.getScope(),
                promotion.getDiscountType(),
                promotion.getDiscountValue(),
                promotion.getMinBillAmount(),
                promotion.getMaxDiscountAmount(),
                promotion.getStartAt(),
                promotion.getEndAt(),
                promotion.getBranchId(),
                promotion.getPriority(),
                promotion.getMarginFloorPercent(),
                promotion.isAllowBelowCost(),
                targets
        );
    }

    /** Whether this promotion is inside its date window at {@code now}. */
    public boolean isRunningAt(LocalDateTime now) {
        return startAt != null && endAt != null
                && !startAt.isAfter(now) && !endAt.isBefore(now);
    }

    /** Whether this promotion covers {@code branchId}. A null branch on the promotion means every branch. */
    public boolean coversBranch(Long branchId) {
        return this.branchId == null || Objects.equals(this.branchId, branchId);
    }

    boolean isLineScope() {
        return scope == PromotionScope.ITEM || scope == PromotionScope.CATEGORY;
    }

    boolean isOrderScope() {
        return scope == PromotionScope.BILL || scope == PromotionScope.CUSTOMER;
    }
}
