package com.chala.posapp.dto.report;

import java.time.LocalDate;

public record ReportExportJobRequest(
        String reportType,
        Long branchId,
        LocalDate from,
        LocalDate to,
        String itemType,
        String orderType,
        String sortBy,
        String sortDirection,
        String emailTo
) {}
