package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.MeasurementUnit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StockResponseWithItems {
    private Long itemId;
    private String barcode;
    private String itemName;
    private BigDecimal costPrice;
    private BigDecimal sellingPrice;
    private Long totalQuantity;
    private BigDecimal displayQuantity;
    private boolean weightItem;
    private MeasurementUnit defaultUnit;

    public StockResponseWithItems(
            Long itemId,
            String barcode,
            String itemName,
            BigDecimal costPrice,
            BigDecimal sellingPrice,
            Long totalQuantity,
            boolean weightItem,
            MeasurementUnit defaultUnit
    ) {
        this.itemId = itemId;
        this.barcode = barcode;
        this.itemName = itemName;
        this.costPrice = costPrice;
        this.sellingPrice = sellingPrice;
        this.totalQuantity = totalQuantity;
        this.weightItem = weightItem;
        this.defaultUnit = defaultUnit;
    }
}
