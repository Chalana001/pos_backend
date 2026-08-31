package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class ReturnTrendPoint {
    private String label;           // "2024-01-15" or "2024-01"
    private BigDecimal saleReturns;
    private BigDecimal purchaseReturns;
    private BigDecimal total;
}
