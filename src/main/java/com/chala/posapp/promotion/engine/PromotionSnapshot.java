package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.Promotion;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.StackingMode;

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
        PromotionEffectType effectType,
        BigDecimal buyQty,
        BigDecimal getQty,
        StackingMode stackingMode,
        boolean allowManualStacking,
        List<TargetSnapshot> targets,
        List<TierSnapshot> tiers,
        List<ScheduleSnapshot> schedules
) {

    public static PromotionSnapshot from(Promotion promotion) {
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
                promotion.getEffectType() == null ? PromotionEffectType.DISCOUNT : promotion.getEffectType(),
                promotion.getBuyQty(),
                promotion.getGetQty(),
                promotion.getStackingMode() == null ? StackingMode.BEST_ONLY : promotion.getStackingMode(),
                promotion.isAllowManualStacking(),
                promotion.getTargets() == null ? List.of()
                        : promotion.getTargets().stream().map(TargetSnapshot::from).toList(),
                promotion.getTiers() == null ? List.of()
                        : promotion.getTiers().stream().map(TierSnapshot::from).toList(),
                promotion.getSchedules() == null ? List.of()
                        : promotion.getSchedules().stream().map(ScheduleSnapshot::from).toList()
        );
    }

    /**
     * Whether this promotion is inside its date window at {@code now} and, if it has schedules,
     * inside at least one of them.
     */
    public boolean isRunningAt(LocalDateTime now) {
        if (startAt == null || endAt == null || startAt.isAfter(now) || endAt.isBefore(now)) {
            return false;
        }
        if (schedules == null || schedules.isEmpty()) {
            return true;
        }
        return schedules.stream().anyMatch(schedule -> schedule.matches(now));
    }

    /** Whether this promotion covers {@code branchId}. A null branch on the promotion means every branch. */
    public boolean coversBranch(Long branchId) {
        return this.branchId == null || Objects.equals(this.branchId, branchId);
    }

    /**
     * Cart-level effects are evaluated with the bill even when their targets are items, because
     * "any three for 1,000" cannot be decided one line at a time.
     */
    boolean isLineLevel() {
        return (scope == PromotionScope.ITEM || scope == PromotionScope.CATEGORY) && !effectType.isCartLevel();
    }

    boolean isOrderLevel() {
        return scope == PromotionScope.BILL || scope == PromotionScope.CUSTOMER || effectType.isCartLevel();
    }

    boolean matchesTargetsByItem() {
        return scope == PromotionScope.ITEM || scope == PromotionScope.CATEGORY;
    }
}
