package com.chala.posapp.dto.purchaseReturns;

import com.chala.posapp.entity.ReturnStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PurchaseReturnResponse {
    private Long id;
    private String debitNoteNo;
    private Long purchaseId;
    private String purchaseInvoiceNo;
    private Long supplierId;
    private String supplierName;
    private Long grnId;
    private String grnNo;
    private Long branchId;
    private String branchName;
    private String processedByUsername;
    private ReturnStatus status;
    private BigDecimal totalReturnAmount;
    private String reason;
    private String note;
    private LocalDateTime createdAt;
    private List<PurchaseReturnItemResponse> items;
}
