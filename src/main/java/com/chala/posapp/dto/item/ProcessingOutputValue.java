package com.chala.posapp.dto.item;

import com.chala.posapp.entity.ItemType;
import com.chala.posapp.entity.MeasurementUnit;

import java.math.BigDecimal;

/**
 * Immutable, response-ready processing-output value. One value represents one
 * relationship row, preserving the legacy mapper's one-row-to-one-response
 * behavior and source-local link-id order.
 */
public record ProcessingOutputValue(
        Long outputItemId,
        String outputBarcode,
        String outputName,
        ItemType itemType,
        MeasurementUnit defaultUnit,
        BigDecimal defaultQty,
        MeasurementUnit defaultQtyUnit,
        BigDecimal defaultSellingPrice,
        boolean waste) {

    /** Builds the existing public response DTO without changing its contract. */
    public StockProcessingOutputLinkResponse toResponse() {
        return StockProcessingOutputLinkResponse.builder()
                .outputItemId(outputItemId)
                .outputBarcode(outputBarcode)
                .outputName(outputName)
                .itemType(itemType)
                .defaultUnit(defaultUnit)
                .defaultQty(defaultQty)
                .defaultQtyUnit(defaultQtyUnit)
                .defaultSellingPrice(defaultSellingPrice)
                .waste(waste)
                .build();
    }
}
