package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// RPT-09: Warranty claims by item and status for a date range
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarrantyReportResponse {
    private Long   itemId;
    private String itemName;
    private String barcode;
    private long   totalWarranties;
    private long   activeCount;
    private long   claimedCount;
    private long   expiredCount;
    private long   voidCount;
}
