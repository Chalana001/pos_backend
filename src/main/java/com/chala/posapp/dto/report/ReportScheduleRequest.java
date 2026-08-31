package com.chala.posapp.dto.report;

import com.chala.posapp.entity.ReportScheduleFrequency;

import java.time.LocalDateTime;

public record ReportScheduleRequest(
        ReportExportJobRequest report,
        ReportScheduleFrequency frequency,
        LocalDateTime nextRunAt,
        String emailTo
) {}
