package com.chala.posapp.dto.order;

import com.chala.posapp.entity.MeasurementUnit;
import com.chala.posapp.entity.WarrantyPeriodUnit;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderItemResponse {
    private Long itemId;
    private String barcode;
    private String itemName;
    private String altName;
    private Long batchId;
    private BigDecimal qty;
    private MeasurementUnit qtyUnit;
    private double unitPrice;
    private String discountType;
    private double discountValue;
    private Long promotionId;
    private String promotionName;
    private double promotionDiscountAmount;
    private boolean promotionApplied;
    private double finalUnitPrice;
    private double lineTotal;
    private String warrantyLabel;
    private Integer warrantyPeriodValue;
    private WarrantyPeriodUnit warrantyPeriodUnit;
}
