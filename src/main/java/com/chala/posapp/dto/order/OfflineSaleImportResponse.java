package com.chala.posapp.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfflineSaleImportResponse {
    private String clientSaleId;
    private boolean success;
    private Long orderId;
    private String invoiceNo;
    private String message;
}
