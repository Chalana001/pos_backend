package com.chala.posapp.dto;

public interface LowStockView {
    Long getItemId();
    String getBarcode();
    String getItemName();
    Integer getStockQty();
    Integer getReorderLevel();
}
