package com.chala.posapp.dto.report;

import com.chala.posapp.entity.ReportExportStatus;

import java.time.LocalDateTime;

public record ReportExportJobResponse(
        Long id,
        String reportType,
        ReportExportStatus status,
        String fileName,
        Long fileSize,
        String errorMessage,
        String emailTo,
        LocalDateTime emailDeliveredAt,
        int attemptCount,
        int maxAttempts,
        LocalDateTime nextAttemptAt,
        Long scheduleId,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        boolean downloadable
) {}
