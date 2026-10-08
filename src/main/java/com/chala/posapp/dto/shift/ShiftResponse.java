package com.chala.posapp.dto.shift;

import com.chala.posapp.entity.ShiftStatus;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ShiftResponse {
    private Long id;
    private Long branchId;
    private String branchName;
    private String branchAddress;
    private String branchPhone;
    private String branchLogo;
    private Long cashierUserId;
    private String cashierName;

    private ShiftStatus status;

    private double openingCash;
    private double totalExpenses;
    private double totalCashDrops;

    private Double expectedCash;
    private Double countedCash;
    private Double cashDifference;
    private double cashSales;

    /** Non-cash tender and cash credit-collection buckets (V60); informational,
     * not part of Expected Cash. */
    private double cardSales;
    private double bankSales;
    private double cashCollections;

    private String openNote;
    private String closeNote;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
}
