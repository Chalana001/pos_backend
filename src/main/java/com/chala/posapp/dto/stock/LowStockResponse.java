package com.chala.posapp.dto.stock;

public interface LowStockResponse {
    Long getItemId();
    String getItemName();
    Integer getTotalQty();
    Integer getReorderLevel();
}
