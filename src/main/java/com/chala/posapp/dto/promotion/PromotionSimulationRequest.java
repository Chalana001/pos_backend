package com.chala.posapp.dto.promotion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * "What would this promotion have done?", replayed against the last {@code days} of completed
 * sales, on its own, before anyone activates it.
 */
@Data
public class PromotionSimulationRequest {

    @Valid
    @NotNull
    private PromotionRequest promotion;

    @Min(1)
    @Max(365)
    private int days = 30;

    /** Null or 0 means every branch. */
    private Long branchId;
}
