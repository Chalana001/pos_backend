package com.chala.posapp.dto.report;

import lombok.*;

import java.time.LocalDate;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SalesTrendPoint {
    private LocalDate date;
    private double sales;
    private long orders;
}
