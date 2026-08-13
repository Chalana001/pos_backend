package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// RPT-03: Z-Report / Shift Summary for a single closed shift
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShiftSummaryResponse {
    private Long          shiftId;
    private Long          branchId;
    private Long          cashierUserId;
    private String        cashierUsername;
    private String        shiftStatus;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;

    // Sales
    private double cashSales;
    private double creditSales;
    private double totalSales;
    private double totalDiscount;
    private long   orderCount;

    // Cash flow
    private double openingCash;
    private double totalCashDrops;
    private double totalExpenses;

    // Closing
    private double expectedClosingCash;   // openingCash + cashSales - expenses - cashDrops
    private double countedCash;
    private double cashDifference;        // counted - expected (negative = short)

    private String openNote;
    private String closeNote;
}
