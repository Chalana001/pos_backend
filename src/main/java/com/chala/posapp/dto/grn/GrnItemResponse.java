package com.chala.posapp.dto.grn;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class GrnItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private Integer qty;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private BigDecimal lineTotal;
}