package com.chala.posapp.entity;

import com.chala.posapp.util.StorageClock;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "report_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportSchedule extends TenantEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by_user_id", nullable = false)
    private Long requestedByUserId;

    @Column(name = "requested_by_username", nullable = false, length = 100)
    private String requestedByUsername;

    @Column(name = "report_type", nullable = false, length = 30)
    private String reportType;

    @Column(name = "parameters_json", nullable = false, columnDefinition = "TEXT")
    private String parametersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportScheduleFrequency frequency;

    @Column(name = "email_to", length = 320)
    private String emailTo;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = StorageClock.now();
    }
}
