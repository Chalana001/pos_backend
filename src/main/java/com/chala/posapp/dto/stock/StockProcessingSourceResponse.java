package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockProcessingSourceResponse {
    private Long id;
    private String barcode;
    private String name;
    private ItemType itemType;
    private MeasurementUnit defaultUnit;
    private BigDecimal availableQty;
    private Integer availableBaseQty;
    private List<StockBatchResponse> batches;
}
