package com.chala.posapp.entity;

public enum StockTransferStatus {
    REQUESTED,   // created by Transfer Out
    RECEIVED,    // approved by receiving branch
    CANCELED     // canceled by sender/admin
}
