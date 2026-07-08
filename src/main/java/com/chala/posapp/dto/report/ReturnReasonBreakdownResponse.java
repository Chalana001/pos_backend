package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class ReturnReasonBreakdownResponse {
    private String reason;
    private long count;
    private BigDecimal totalAmount;
    private String type;   // "SALE" | "PURCHASE"
}
