package com.chala.posapp.entity;

/** Financial category of a payment method. Drives reporting, never the name. */
public enum PaymentCategory {
    CASH,
    CARD,
    BANK_TRANSFER,
    MOBILE_DIGITAL,
    CREDIT,
    OTHER
}
