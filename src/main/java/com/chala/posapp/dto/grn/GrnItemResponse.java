package com.chala.posapp.dto.grn;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class GrnItemResponse {
    private Long id;        // GrnItem row ID — used by purchase return to identify the exact line
    private Long itemId;    // Product/Item ID
    private String barcode;
    private String itemName;
    private String altName;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal lineTotal;
}
