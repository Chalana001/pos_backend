package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionEffectType;
import com.chala.posapp.entity.StackingMode;
import com.chala.posapp.entity.PromotionScope;
import com.chala.posapp.entity.PromotionStatus;
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
    private PromotionEffectType effectType;
    private BigDecimal buyQty;
    private BigDecimal getQty;
    private StackingMode stackingMode;
    private boolean allowManualStacking;
    private List<PromotionTierDto> tiers;
    private List<PromotionScheduleDto> schedules;
    private Integer maxTotalRedemptions;
    private Integer maxRedemptionsPerCustomer;
    private BigDecimal budgetAmount;
    private int timesRedeemed;
    private BigDecimal budgetConsumed;
    /** How many codes gate this promotion; zero means it applies automatically. */
    private int codeCount;
    private boolean exhausted;
    /** DRAFT, PENDING_APPROVAL, ACTIVE or PAUSED — the stored half of the lifecycle. */
    private PromotionStatus status;
    /** Live, scheduled, ended, exhausted and so on — derived, what the list shows. */
    private String lifecycle;
    private Long createdBy;
    private Long updatedBy;
    private Long submittedBy;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String approvalNote;
    /** Item ids alone, for clients written before per-item pricing. Mirrors {@link #items}. */
    private List<Long> itemIds;
    /** Item targets with whatever price or rate each one carries. */
    private List<PromotionItemLine> items;
    private List<Long> categoryIds;
    private List<Long> subCategoryIds;
    private List<Long> customerIds;
}
