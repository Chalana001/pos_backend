package com.chala.posapp.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegacySalesImportCommitResponse {
    private int importedSales;
    private int importedItems;
    private double importedGrandTotal;
    private List<String> invoiceNos;
}
