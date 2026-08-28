package com.chala.posapp.dto.saas;

import java.time.LocalDateTime;

public record AuditEntryResponse(
        Long id,
        String actor,
        String action,
        String targetType,
        String targetId,
        String summary,
        String details,
        String ipAddress,
        LocalDateTime createdAt
) {
}
