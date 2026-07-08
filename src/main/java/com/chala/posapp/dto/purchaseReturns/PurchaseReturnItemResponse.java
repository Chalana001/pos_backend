package com.chala.posapp.dto.purchaseReturns;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PurchaseReturnItemResponse {
    private Long id;
    private Long grnItemId;
    private Long itemId;
    private String itemName;
    private String barcode;
    private int returnQty;
    private BigDecimal costPrice;
    private BigDecimal returnLineAmount;
    private boolean stockDeducted;
}
