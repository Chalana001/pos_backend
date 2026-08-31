package com.chala.posapp.dto.purchase;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PurchaseImportLookupResponse {
    private PurchaseImportRowData row;
}
