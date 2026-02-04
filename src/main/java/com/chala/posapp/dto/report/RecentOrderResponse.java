package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class RecentOrderResponse {
    private Long id;
    private String invoiceNo;
    private double totalAmount;
    private String type;
    private LocalDateTime date;
}