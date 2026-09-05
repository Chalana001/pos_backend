package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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

    @Positive
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
