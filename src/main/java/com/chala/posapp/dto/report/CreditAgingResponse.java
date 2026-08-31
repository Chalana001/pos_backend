package com.chala.posapp.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// RPT-07: Customer credit aging — outstanding credit split into buckets
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditAgingResponse {
    private Long          customerId;
    private String        customerName;
    private String        phone;
    private double        totalDue;
    private double        bucket0to30;   // 0-30 days overdue
    private double        bucket31to60;  // 31-60 days overdue
    private double        bucket61to90;  // 61-90 days overdue
    private double        bucket91plus;  // 91+ days overdue
    private LocalDateTime oldestOrderAt; // date of oldest unpaid order
    private String oldestInvoiceNo;
    private long unpaidInvoiceCount;
    private LocalDateTime lastPaymentAt;
    private Double creditLimit;
    private boolean overCreditLimit;
    private String priority;
}
