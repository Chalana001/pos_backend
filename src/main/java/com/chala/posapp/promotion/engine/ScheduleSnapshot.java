package com.chala.posapp.promotion.engine;

import com.chala.posapp.entity.PromotionSchedule;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * A recurring window inside a promotion's date range. Monday is bit 0, Sunday bit 6, a mask of 0
 * is every day; a window whose end is before its start runs overnight.
 */
public record ScheduleSnapshot(int daysOfWeek, LocalTime startTime, LocalTime endTime) {

    public static ScheduleSnapshot from(PromotionSchedule schedule) {
        return new ScheduleSnapshot(schedule.getDaysOfWeek(), schedule.getStartTime(), schedule.getEndTime());
    }

    public boolean matches(LocalDateTime now) {
        if (daysOfWeek != PromotionSchedule.EVERY_DAY) {
            int bit = 1 << (now.getDayOfWeek().getValue() - 1);
            if ((daysOfWeek & bit) == 0) {
                return false;
            }
        }
        if (startTime == null || endTime == null) {
            return true;
        }
        LocalTime time = now.toLocalTime();
        if (!endTime.isBefore(startTime)) {
            return !time.isBefore(startTime) && !time.isAfter(endTime);
        }
        // Overnight: 22:00 -> 02:00 covers late evening and the small hours.
        return !time.isBefore(startTime) || !time.isAfter(endTime);
    }
}
