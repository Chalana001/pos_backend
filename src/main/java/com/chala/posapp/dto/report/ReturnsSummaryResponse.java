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
    //
    // WARNING: totalReturnAmount adds two opposite cash flows together —
    // saleReturnTotal is money refunded OUT to customers, purchaseReturnTotal is
    // value recovered IN from suppliers. The sum is not a meaningful figure and
    // must not be presented as "total returns" to a shop owner. It is kept only
    // for API compatibility; the POS frontend does not display it. Use the two
    // components separately.
    private BigDecimal totalReturnAmount;

    private BigDecimal grossSales;

    // Standard accounting definition: gross sales less customer refunds.
    // Purchase returns are deliberately NOT subtracted here — they reduce
    // inventory cost (COGS), not revenue.
    private BigDecimal netRevenue;            // grossSales - saleReturnTotal

    // Returns RAISED in the period over orders PLACED in the period. A return can
    // belong to an order from an earlier period, so the two populations are not
    // the same set and this can exceed 100%. Treat as an activity indicator.
    private double returnRate;               // saleReturnCount / totalOrders * 100
}
