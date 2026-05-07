package com.chala.posapp.dto.stock;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

public interface LowStockResponse {
    Long getItemId();
    String getBarcode();
    String getItemName();
    Long getTotalQty();
    Integer getReorderLevel();
    ItemType getItemType();
    MeasurementUnit getDefaultUnit();
}
