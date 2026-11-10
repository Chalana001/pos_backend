package com.chala.posapp.service;

import com.chala.posapp.dto.branch.BranchPurchaseRequest;
import com.chala.posapp.dto.grn.GrnItemRequest;
import com.chala.posapp.exception.DuplicatePurchaseRowsException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PurchaseDuplicateValidator {
    private PurchaseDuplicateValidator() {}

    static List<DuplicatePurchaseRowsException.Duplicate> findDuplicates(List<BranchPurchaseRequest> branches) {
        Map<RowKey, Integer> firstByKey = new LinkedHashMap<>();
        List<DuplicatePurchaseRowsException.Duplicate> duplicates = new ArrayList<>();
        int rowIndex = 0;
        for (BranchPurchaseRequest branch : branches) {
            if (branch.getItems() == null) continue;
            for (GrnItemRequest row : branch.getItems()) {
                RowKey key = new RowKey(row.getItemId(), money(row.getCostPrice()), money(row.getSellingPrice()),
                        row.getExpiryDate(), quantity(row.getQty()));
                Integer firstIndex = firstByKey.putIfAbsent(key, rowIndex);
                if (firstIndex != null) {
                    duplicates.add(new DuplicatePurchaseRowsException.Duplicate(rowIndex, firstIndex, row.getItemId()));
                }
                rowIndex++;
            }
        }
        return duplicates;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private record RowKey(Long itemId, BigDecimal costPrice, BigDecimal sellingPrice,
                          LocalDate expiryDate, BigDecimal quantity) {}
}
