package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

// RPT-10: Inter-branch stock transfer record for the transfer report
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockTransferReportResponse {
    private Long          transferId;
    private String        transferNo;
    private Long          fromBranchId;
    private String        fromBranchName;
    private Long          toBranchId;
    private String        toBranchName;
    private String        status;
    private LocalDateTime createdAt;
    private String        createdByUsername;
    private List<TransferItemLine> items;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferItemLine {
        private Long   itemId;
        private String itemName;
        private String barcode;
        private double quantity;
        private String unit;
    }
}
