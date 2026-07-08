package com.chala.posapp.dto.item;

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
public class StockProcessingOutputLinkResponse {
    private Long outputItemId;
    private String outputBarcode;
    private String outputName;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private BigDecimal defaultQty;
    private MeasurementUnit defaultQtyUnit;
    private BigDecimal defaultSellingPrice;  // effective: link-level override, else item selling price
    private boolean waste;
}
