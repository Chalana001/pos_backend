package com.chala.posapp.entity;

public enum StockAdjustmentType {
    EXPIRED,   // remove
    DAMAGED,   // remove
    LOST,      // remove
    FOUND,     // add
    MANUAL     // add/remove (depends on qtyChange sign)
}
