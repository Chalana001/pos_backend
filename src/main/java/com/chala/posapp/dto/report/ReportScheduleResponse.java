package com.chala.posapp.dto.report;

import com.chala.posapp.entity.ReportScheduleFrequency;

import java.time.LocalDateTime;

public record ReportScheduleResponse(
        Long id,
        String reportType,
        ReportScheduleFrequency frequency,
        String emailTo,
        boolean enabled,
        LocalDateTime nextRunAt,
        LocalDateTime lastRunAt,
        LocalDateTime createdAt
) {}
