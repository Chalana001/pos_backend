package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlowResponse {
    private double cashSales;
    private double creditCollections;
    private double totalInflows;
    private double expenses;
    private double purchasePayments;
    private double supplierPayments;
    private double cashRefunds;
    private double totalOutflows;
    private double netCashMovement;
    private double cashDrops;
    private List<DailyMovement> dailyMovements;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyMovement {
        private LocalDate date;
        private double inflows;
        private double outflows;
        private double netMovement;
    }
}
