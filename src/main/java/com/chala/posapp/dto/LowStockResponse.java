package com.chala.posapp.dto;

public interface LowStockResponse {
    Long getItemId();
    String getItemName();
    Integer getTotalQty();
    Integer getReorderLevel();
}
