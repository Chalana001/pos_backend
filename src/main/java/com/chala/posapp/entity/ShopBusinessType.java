package com.chala.posapp.entity;

/**
 * What kind of shop a tenant is. Drives the module preset offered at onboarding —
 * a retail shop gets dine-in and kitchen tickets switched off by default, a restaurant
 * gets them on and barcode label printing off.
 */
public enum ShopBusinessType {
    RETAIL,
    RESTAURANT,
    HYBRID
}
