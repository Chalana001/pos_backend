package com.chala.posapp.dto.dining;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.WarrantyPeriodUnit;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PendingOrderItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private Long batchId;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;
    private double unitPrice;
    private String discountType;
    private double discountValue;
    private double finalUnitPrice;
    private double lineTotal;
    private String warrantyLabel;
    private Integer warrantyPeriodValue;
    private WarrantyPeriodUnit warrantyPeriodUnit;
}
