package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockTransferItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private String altName;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;
}
