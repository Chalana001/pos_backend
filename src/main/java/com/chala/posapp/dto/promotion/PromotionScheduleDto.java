package com.chala.posapp.dto.promotion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * A recurring window inside the promotion's date range. {@code daysOfWeek} is a bitmask,
 * Monday = 1 through Sunday = 64, 0 for every day. Leave both times empty for all day.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionScheduleDto {
    private int daysOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
}
