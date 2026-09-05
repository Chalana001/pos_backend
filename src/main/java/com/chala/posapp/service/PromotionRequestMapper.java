package com.chala.posapp.service;

import com.chala.posapp.dto.promotion.PromotionItemLine;
import com.chala.posapp.dto.promotion.PromotionRequest;
import com.chala.posapp.dto.promotion.PromotionScheduleDto;
import com.chala.posapp.dto.promotion.PromotionTierDto;
import com.chala.posapp.promotion.engine.PromotionSnapshot;
import com.chala.posapp.promotion.engine.ScheduleSnapshot;
import com.chala.posapp.promotion.engine.TargetSnapshot;
import com.chala.posapp.promotion.engine.TierSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An unsaved promotion as the engine would see it.
 *
 * <p>The simulator and the pre-save check both need to price with a promotion that does not
 * exist yet. Building the snapshot straight from the request keeps them honest: they run the
 * exact terms on screen, not a saved approximation of them.
 */
final class PromotionRequestMapper {

    private PromotionRequestMapper() {
    }

    /**
     * @param startAt / endAt override the request's dates — a replay over last month must not be
     *                 gated by a window that starts next week
     */
    static PromotionSnapshot snapshot(PromotionRequest request, Long id, LocalDateTime startAt, LocalDateTime endAt) {
        List<TargetSnapshot> targets = new ArrayList<>();
        switch (request.getScope()) {
            case ITEM -> {
                for (PromotionItemLine line : request.resolvedItemLines()) {
                    if (line != null && line.getId() != null) {
                        targets.add(new TargetSnapshot(line.getId(), null, null, null,
                                line.getOfferPrice(), line.getDiscountType(), line.getDiscountValue()));
                    }
                }
            }
            case CATEGORY -> {
                if (request.getCategoryIds() != null) {
                    request.getCategoryIds().forEach(c -> targets.add(new TargetSnapshot(null, c, null, null, null, null, null)));
                }
                if (request.getSubCategoryIds() != null) {
                    request.getSubCategoryIds().forEach(s -> targets.add(new TargetSnapshot(null, null, s, null, null, null, null)));
                }
            }
            case CUSTOMER -> {
                if (request.getCustomerIds() != null) {
                    request.getCustomerIds().forEach(c -> targets.add(new TargetSnapshot(null, null, null, c, null, null, null)));
                }
            }
            case BILL -> { }
        }

        List<TierSnapshot> tiers = new ArrayList<>();
        if (request.getTiers() != null) {
            for (PromotionTierDto tier : request.getTiers()) {
                tiers.add(new TierSnapshot(tier.getMinQty(), tier.getMinAmount(), tier.getDiscountType(), tier.getDiscountValue()));
            }
        }
        List<ScheduleSnapshot> schedules = new ArrayList<>();
        if (request.getSchedules() != null) {
            for (PromotionScheduleDto schedule : request.getSchedules()) {
                schedules.add(new ScheduleSnapshot(schedule.getDaysOfWeek(), schedule.getStartTime(), schedule.getEndTime()));
            }
        }

        return new PromotionSnapshot(
                id,
                request.getName() == null ? "Unsaved promotion" : request.getName(),
                request.getScope(),
                request.getDiscountType(),
                BigDecimal.valueOf(request.getDiscountValue()),
                BigDecimal.valueOf(Math.max(0, request.getMinBillAmount())),
                BigDecimal.valueOf(Math.max(0, request.getMaxDiscountAmount())),
                startAt,
                endAt,
                request.getBranchId() == null || request.getBranchId() <= 0 ? null : request.getBranchId(),
                request.getPriority(),
                request.getMarginFloorPercent(),
                request.isAllowBelowCost(),
                request.resolvedEffectType(),
                request.getBuyQty(),
                request.getGetQty(),
                request.resolvedStackingMode(),
                request.resolvedAllowManualStacking(),
                targets, tiers, schedules
        );
    }
}
