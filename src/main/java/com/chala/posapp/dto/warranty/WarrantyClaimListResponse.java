package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyClaimActionType;
import com.chala.posapp.entity.WarrantyClaimStatus;
import com.chala.posapp.entity.WarrantyStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WarrantyClaimListResponse {
    private Long id;
    private String claimNo;
    private Long warrantyId;
    private Long branchId;
    private String warrantyNo;
    private String invoiceNo;
    private String customerName;
    private String itemName;
    private String altName;
    private String barcode;
    private WarrantyStatus warrantyStatus;
    private WarrantyClaimActionType actionType;
    private WarrantyClaimStatus status;
    private String issueDescription;
    private LocalDateTime receivedAt;
    private LocalDateTime completedAt;
}
