package com.chala.posapp.dto.stock;

public record StockSummaryResponse(
        Long itemId,
        String itemName,
        String barcode,
        Long qty
) {}

