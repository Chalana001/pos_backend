package com.chala.posapp.dto;

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

    private String openNote;
    private String closeNote;

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
}