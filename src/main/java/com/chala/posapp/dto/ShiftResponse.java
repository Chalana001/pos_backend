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
    private String branchName;     // ✅ ඇඩ්මින්ට පෙන්වන්න අලුතින් එකතු කළා
    private Long cashierUserId;
    private String cashierName;    // ✅ ඇඩ්මින්ට පෙන්වන්න අලුතින් එකතු කළා

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