package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockProcessingOutputResponse {
    private Long outputItemId;
    private String outputItemName;
    private String outputBarcode;
    private ItemType itemType;
    private Integer quantity;
    private BigDecimal displayQty;
    private MeasurementUnit qtyUnit;
    private boolean waste;
    private BigDecimal allocatedCost;
    private BigDecimal sellingPrice;
    private Long createdBatchId;
}
