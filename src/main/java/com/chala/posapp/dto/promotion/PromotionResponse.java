package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionScope;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PromotionResponse {
    private Long id;
    private String name;
    private PromotionScope scope;
    private DiscountType discountType;
    private double discountValue;
    private double minBillAmount;
    private double maxDiscountAmount;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Long branchId;
    private boolean active;
    private int priority;
    private BigDecimal marginFloorPercent;
    private boolean allowBelowCost;
    /** Item ids alone, for clients written before per-item pricing. Mirrors {@link #items}. */
    private List<Long> itemIds;
    /** Item targets with whatever price or rate each one carries. */
    private List<PromotionItemLine> items;
    private List<Long> categoryIds;
    private List<Long> subCategoryIds;
    private List<Long> customerIds;
}
