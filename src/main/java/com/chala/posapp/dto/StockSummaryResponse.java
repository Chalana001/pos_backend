package com.chala.posapp.dto;

public record StockSummaryResponse(
        Long itemId,
        String itemName,
        String barcode,
        Long qty
) {}

