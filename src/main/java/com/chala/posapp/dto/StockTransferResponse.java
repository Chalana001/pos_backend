package com.chala.posapp.dto;

import com.chala.posapp.entity.StockTransferStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockTransferResponse {

    private Long id;
    private String transferNo;

    // IDs විතරක් නෙමෙයි, නම් ටිකත් යවන්න
    private Long fromBranchId;
    private String fromBranchName; // ✨ New

    private Long toBranchId;
    private String toBranchName;   // ✨ New

    private StockTransferStatus status;

    private Long requestedByUserId;
    private String requestedByUserName; // ✨ New (Optional)

    private Long receivedByUserId;
    private String receivedByUserName;  // ✨ New (Optional)

    private String note;
    private String cancelReason;

    private LocalDateTime requestedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime canceledAt;

    private List<StockTransferItemResponse> items;
}