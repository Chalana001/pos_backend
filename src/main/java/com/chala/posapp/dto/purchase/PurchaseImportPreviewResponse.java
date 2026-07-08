package com.chala.posapp.dto.purchase;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PurchaseImportPreviewResponse {
    private int totalRows;
    private int readyCount;
    private int notFoundCount;
    private int ambiguousCount;
    private int invalidCount;
    private List<PurchaseImportRowData> rows;
}
