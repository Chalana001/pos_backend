package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "forecast_snapshots")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ForecastSnapshot extends TenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "export_job_id", nullable = false, unique = true) private Long exportJobId;
    @Column(name = "branch_id") private Long branchId;
    @Column(name = "forecast_days", nullable = false) private int forecastDays;
    @Column(name = "window_start", nullable = false) private LocalDateTime windowStart;
    @Column(name = "window_end", nullable = false) private LocalDateTime windowEnd;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "evaluated_at") private LocalDateTime evaluatedAt;
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<ForecastSnapshotItem> items = new ArrayList<>();
    @PrePersist void onPersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
