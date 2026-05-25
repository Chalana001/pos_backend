package com.chala.posapp.dto.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LegacySalesImportIssue {
    private String severity;
    private String code;
    private String saleNo;
    private Integer rowNumber;
    private String itemName;
    private String message;
}
