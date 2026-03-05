package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.stock.StockTransferStatus;
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
    private String fromBranchName;
    private Long toBranchId;
    private String toBranchName;
    private StockTransferStatus status;
    private Long requestedByUserId;
    private String requestedByUserName;
    private Long receivedByUserId;
    private String receivedByUserName;
    private String note;
    private String cancelReason;
    private LocalDateTime requestedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime canceledAt;

    private List<StockTransferItemResponse> items;
}