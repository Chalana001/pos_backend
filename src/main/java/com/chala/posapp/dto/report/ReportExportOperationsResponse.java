package com.chala.posapp.dto.report;

import java.util.List;

public record ReportExportOperationsResponse(
        String status,
        long failed,
        long stale,
        long queued,
        List<TenantStatus> tenants
) {
    public record TenantStatus(String tenantId, String status, long failed, long stale, long queued, String error) {}
}
