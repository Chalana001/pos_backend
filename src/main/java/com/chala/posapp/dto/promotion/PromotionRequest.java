package com.chala.posapp.dto.promotion;

import com.chala.posapp.entity.DiscountType;
import com.chala.posapp.entity.PromotionScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

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

    private List<Long> itemIds;
    private List<Long> categoryIds;
    private List<Long> subCategoryIds;
    private List<Long> customerIds;
}
