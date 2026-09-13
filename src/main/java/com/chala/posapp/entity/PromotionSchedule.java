package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

/**
 * A recurring window inside a promotion's date range, the shape of happy hour, weekday lunch,
 * or weekend-only.
 *
 * <p>A promotion with no schedules runs whenever its dates say so. With one or more, it runs
 * only while some schedule matches. {@code daysOfWeek} is a bitmask, Monday = 1 through
 * Sunday = 64, with 0 meaning every day. A window whose end is before its start runs overnight.
 * Times are in the shop's local clock, the same one {@code Order.createdAt} is stamped in.
 */
@Entity
@Table(
        name = "promotion_schedules",
        indexes = @Index(name = "idx_promotion_schedule_promotion", columnList = "promotion_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionSchedule extends TenantEntity {

    public static final int EVERY_DAY = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Column(name = "days_of_week", nullable = false)
    @Builder.Default
    private int daysOfWeek = EVERY_DAY;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;
}
