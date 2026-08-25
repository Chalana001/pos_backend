package com.chala.posapp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * A cash drop recorded outside any shift — e.g. an owner banking
 * already-collected cash after every shift for the day is closed.
 *
 * Unlike a normal cash drop, this has no shift to derive branchId from, so
 * the branch is explicit. Purely record-keeping: never reduces any shift's
 * Expected Cash.
 */
@Data
public class RecordOutsideShiftCashDropRequest {

    @NotNull
    private Long branchId;

    @Min(1)
    private double amount;

    @NotBlank
    private String reason;

    private Long bankAccountId;
}
