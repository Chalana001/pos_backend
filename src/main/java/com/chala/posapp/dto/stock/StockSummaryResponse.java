package com.chala.posapp.dto.stock;

public record StockSummaryResponse(
        Long itemId,
        String itemName,
        String altName,
        String barcode,
        Long qty
) {}

