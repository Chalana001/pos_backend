package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class TopReturnedItemResponse {
    private Long itemId;
    private String itemName;
    private String barcode;
    private long returnCount;       // number of return transactions containing this item
    private long totalReturnedQty;
    private BigDecimal totalReturnAmount;
}
