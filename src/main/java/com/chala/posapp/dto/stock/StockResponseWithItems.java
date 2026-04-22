package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType; // ✅ Enum එක Import කරන්න
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

    // ✅ boolean weightItem එක වෙනුවට අලුත් ItemType එක දැම්මා
    private ItemType itemType;
    private MeasurementUnit defaultUnit;

    public StockResponseWithItems(
            Long itemId,
            String barcode,
            String itemName,
            BigDecimal costPrice,
            BigDecimal sellingPrice,
            Long totalQuantity,
            ItemType itemType, // ✅ මෙතනත් වෙනස් කළා
            MeasurementUnit defaultUnit
    ) {
        this.itemId = itemId;
        this.barcode = barcode;
        this.itemName = itemName;
        this.costPrice = costPrice;
        this.sellingPrice = sellingPrice;
        this.totalQuantity = totalQuantity;
        this.itemType = itemType; // ✅ මෙතනත් වෙනස් කළා
        this.defaultUnit = defaultUnit;
    }
}