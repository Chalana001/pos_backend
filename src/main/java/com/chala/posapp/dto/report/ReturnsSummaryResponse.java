package com.chala.posapp.dto.report;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class ReturnsSummaryResponse {
    // Sale returns
    private long saleReturnCount;
    private BigDecimal saleReturnTotal;
    private long saleReturnItemCount;

    // Purchase returns
    private long purchaseReturnCount;
    private BigDecimal purchaseReturnTotal;
    private long purchaseReturnItemCount;

    // Combined
    private BigDecimal totalReturnAmount;
    private BigDecimal grossSales;
    private BigDecimal netRevenue;            // grossSales - saleReturnTotal
    private double returnRate;               // saleReturnCount / totalOrders * 100
}
