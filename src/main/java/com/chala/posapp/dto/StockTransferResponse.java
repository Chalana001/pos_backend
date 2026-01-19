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

    private Long fromBranchId;
    private Long toBranchId;

    private StockTransferStatus status;

    private Long requestedByUserId;
    private Long receivedByUserId;

    private String note;
    private String cancelReason;

    private LocalDateTime requestedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime canceledAt;

    private List<StockTransferItemResponse> items;
}
