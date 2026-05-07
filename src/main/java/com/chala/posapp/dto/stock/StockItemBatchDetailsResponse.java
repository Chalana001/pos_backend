package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockItemBatchDetailsResponse {
    private Long batchId;
    private String batchCode;
    private Long branchId;
    private String branchName;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private Integer qty;
    private BigDecimal displayQty;
    private MeasurementUnit displayUnit;
    private LocalDateTime receivedAt;
    private LocalDateTime expiry;
}
