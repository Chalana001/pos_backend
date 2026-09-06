package com.chala.posapp.dto.loyalty;

import lombok.Data;

/** A manual correction: positive to award points, negative to take them back. */
@Data
public class LoyaltyAdjustRequest {
    private int points;
    private String note;
}
