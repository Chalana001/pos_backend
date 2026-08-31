package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.stock.StockProcessingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockProcessingResponse {
    private Long id;
    private Long branchId;
    private String branchName;
    private Long sourceItemId;
    private String sourceItemName;
    private String sourceBarcode;
    private ItemType sourceItemType;
    private Long sourceBatchId;
    private String sourceBatchCode;
    private Integer sourceQty;
    private BigDecimal sourceDisplayQty;
    private MeasurementUnit sourceQtyUnit;
    private BigDecimal sourceCost;
    private StockProcessingStatus status;
    private String cancelReason;
    private Long canceledByUserId;
    private String canceledByUsername;
    private LocalDateTime canceledAt;
    private Long processedByUserId;
    private String processedByUsername;
    private LocalDateTime processedAt;
    private String note;
    private List<StockProcessingOutputResponse> outputs;
}
