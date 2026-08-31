package com.chala.posapp.dto.order;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StockShortageIssue {
    private Long itemId;
    private String itemName;
    private Long stockItemId;
    private String stockItemName;
    private Long batchId;
    private int requiredQuantity;
    private int availableQuantity;
    private int shortageQuantity;
    private MeasurementUnit unit;
}
