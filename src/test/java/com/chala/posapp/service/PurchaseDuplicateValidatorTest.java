package com.chala.posapp.service;

import com.chala.posapp.dto.branch.BranchPurchaseRequest;
import com.chala.posapp.dto.grn.GrnItemRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseDuplicateValidatorTest {
    @Test
    void detectsIdenticalRowsAndEveryRepeatedOccurrence() {
        var result = PurchaseDuplicateValidator.findDuplicates(List.of(branch(
                row(1L, "100.00", "120", "2027-05-01", "10"),
                row(1L, "100", "120.00", "2027-05-01", "10.0"),
                row(1L, "100.000", "120", "2027-05-01", "10"))));

        assertThat(result).extracting("rowIndex").containsExactly(1, 2);
        assertThat(result).extracting("duplicateOfRowIndex").containsExactly(0, 0);
    }

    @Test
    void eachIdentityFieldCanDifferAndEmptyExpiryIsEquivalentToNull() {
        var result = PurchaseDuplicateValidator.findDuplicates(List.of(branch(
                row(1L, "100", "120", null, "10"),
                row(1L, "100", "120", "", "10"),
                row(2L, "100", "120", null, "10"),
                row(1L, "101", "120", null, "10"),
                row(1L, "100", "121", null, "10"),
                row(1L, "100", "120", "2027-05-01", "10"),
                row(1L, "100", "120", null, "11"))));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rowIndex()).isEqualTo(1);
    }

    @Test
    void handlesLargeRowListsInLinearPass() {
        var rows = new java.util.ArrayList<GrnItemRequest>();
        for (int i = 0; i < 101; i++) rows.add(row((long) i, "1", "2", null, "1"));
        rows.add(row(0L, "1.00", "2.00", "", "1.0"));

        var result = PurchaseDuplicateValidator.findDuplicates(List.of(branch(rows.toArray(GrnItemRequest[]::new))));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).rowIndex()).isEqualTo(101);
        assertThat(result.get(0).duplicateOfRowIndex()).isEqualTo(0);
    }

    private static BranchPurchaseRequest branch(GrnItemRequest... items) {
        return branch(List.of(items));
    }

    private static BranchPurchaseRequest branch(List<GrnItemRequest> items) {
        var branch = new BranchPurchaseRequest();
        branch.setItems(items);
        return branch;
    }

    private static GrnItemRequest row(Long itemId, String cost, String sell, String expiry, String qty) {
        var row = new GrnItemRequest();
        row.setItemId(itemId);
        row.setCostPrice(new BigDecimal(cost));
        row.setSellingPrice(new BigDecimal(sell));
        row.setExpiryDate(expiry == null || expiry.isEmpty() ? null : LocalDate.parse(expiry));
        row.setQty(new BigDecimal(qty));
        return row;
    }
}
