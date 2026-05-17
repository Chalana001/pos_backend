package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyClaimActionType;
import com.chala.posapp.entity.WarrantyClaimStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WarrantyClaimResponse {
    private Long id;
    private String claimNo;
    private Long warrantyId;
    private Long branchId;
    private WarrantyClaimActionType actionType;
    private WarrantyClaimStatus status;
    private String issueDescription;
    private String resolutionNote;
    private LocalDateTime receivedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}
