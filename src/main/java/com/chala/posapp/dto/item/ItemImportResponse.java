package com.chala.posapp.dto.item;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ItemImportResponse {
    private boolean success;
    private int totalRows;
    private int readyCount;
    private int importedCount;
    private int errorCount;
    private int skippedCount;
    private List<ItemImportRowData> rows;
}
