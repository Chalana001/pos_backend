package com.chala.posapp.entity;

public enum StockTransferStatus {
    IN_TRANSIT,   // created by Transfer Out
    COMPLETED,    // approved by receiving branch
    CANCELED     // canceled by sender/admin
}
