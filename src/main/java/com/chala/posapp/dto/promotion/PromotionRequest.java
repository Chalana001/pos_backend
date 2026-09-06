package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.entity.PromotionScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PromotionRequest {

    @NotBlank
    private String name;

    @NotNull
    private PromotionScope scope;

    @NotNull
    private DiscountType discountType;

    /** Zero is legitimate for effects that are not a rate — buy-X-get-Y, tiers — so the floor is per effect, in the service. */
    @PositiveOrZero
    private double discountValue;

    private double minBillAmount;

    private double maxDiscountAmount;

    @NotNull
    private LocalDateTime startAt;

    @NotNull
    private LocalDateTime endAt;

    private Long branchId;

    private boolean active = true;

    private int priority = 0;

    /** Minimum gross margin a discounted line must keep, as a percentage. Null means no floor. */
    private BigDecimal marginFloorPercent;

    /** Lets this promotion price below cost on purpose. Below-cost pricing is refused without it. */
    private boolean allowBelowCost;

    /** Which mechanic this is. Absent means DISCOUNT, the original rate-off-list behaviour. */
    private PromotionEffectType effectType;

    /** BUY_X_GET_Y_FREE: units to buy. BUNDLE / CHEAPEST_FREE: units per group. */
    private BigDecimal buyQty;

    /** BUY_X_GET_Y_FREE: units given free. */
    private BigDecimal getQty;

    /** How this combines with other promotions. Absent means BEST_ONLY. */
    private StackingMode stackingMode;

    /** Absent means true: a cashier's line discount may stack on top. */
    private Boolean allowManualStacking;

    /** TIERED only. */
    @Valid
    private List<PromotionTierDto> tiers;

    /** Empty means the promotion runs whenever its dates say so. */
    @Valid
    private List<PromotionScheduleDto> schedules;

    /** Orders this promotion may discount before it stops applying. Null means unlimited. */
    private Integer maxTotalRedemptions;

    /** Orders per customer. Only enforceable when a customer is on the sale. */
    private Integer maxRedemptionsPerCustomer;

    /** Total discount this promotion may give. Null means unlimited. */
    private BigDecimal budgetAmount;

    public PromotionEffectType resolvedEffectType() {
        return effectType == null ? PromotionEffectType.DISCOUNT : effectType;
    }

    public StackingMode resolvedStackingMode() {
        return stackingMode == null ? StackingMode.BEST_ONLY : stackingMode;
    }

    public boolean resolvedAllowManualStacking() {
        return allowManualStacking == null || allowManualStacking;
    }

    /**
     * Item targets without per-item pricing. Superseded by {@link #items} but still accepted:
     * the POS app and the backend are separate repositories that can drift, so an older client
     * must keep working against a newer backend for at least one release. Ignored when
     * {@link #items} is present.
     */
    private List<Long> itemIds;

    /** Item targets, each optionally carrying its own offer price or rate. */
    @Valid
    private List<PromotionItemLine> items;

    private List<Long> categoryIds;
    private List<Long> subCategoryIds;
    private List<Long> customerIds;

    /** Segment targets for a CUSTOMER promotion — a rule instead of a list of people. */
    private List<Long> segmentIds;

    /**
     * The item targets to persist, preferring the richer list. Collapsing the two shapes here
     * keeps every caller — validation, target building, margin checks — from having to know
     * which one the client sent.
     */
    public List<PromotionItemLine> resolvedItemLines() {
        if (items != null && !items.isEmpty()) {
            return items;
        }
        if (itemIds == null) {
            return List.of();
        }
        return itemIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> PromotionItemLine.builder().id(id).build())
                .toList();
    }
}
