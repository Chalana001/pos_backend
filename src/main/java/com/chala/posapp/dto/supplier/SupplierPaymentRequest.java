package com.chala.posapp.dto.supplier;

import com.chala.posapp.entity.CashSource;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SupplierPaymentRequest {
    private Long purchaseId;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    private BigDecimal amount;

    private String paymentMethod;
    private CashSource cashSource;
    private Long cashSourceBranchId;
    private String note;
}
