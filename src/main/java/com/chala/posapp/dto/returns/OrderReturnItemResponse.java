package com.chala.posapp.dto.returns;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderReturnItemResponse {

    private Long id;
    private Long orderItemId;
    private Long itemId;
    private String itemName;
    private String barcode;

    // Returned quantity in display units (what the slip shows)
    private int returnQty;

    private double unitPrice;
    private double finalUnitPrice;

    // returnQty × finalUnitPrice
    private double refundLineAmount;

    // Was stock put back into the batch?
    private boolean stockReversed;
}
