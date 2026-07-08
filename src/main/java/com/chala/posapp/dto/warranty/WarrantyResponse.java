package com.chala.posapp.dto.warranty;

import com.chala.posapp.entity.WarrantyPeriodUnit;
import com.chala.posapp.entity.WarrantyStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WarrantyResponse {
    private Long id;
    private String warrantyNo;
    private Long orderId;
    private Long orderItemId;
    private Long branchId;
    private String invoiceNo;
    private Long customerId;
    private String customerName;
    private Long itemId;
    private String itemName;
    private String altName;
    private String barcode;
    private String warrantyLabel;
    private int periodValue;
    private WarrantyPeriodUnit periodUnit;
    private LocalDate startDate;
    private LocalDate endDate;
    private WarrantyStatus status;
    private LocalDateTime createdAt;
}
